package com.voicenavigation.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.dialogflow.v2.*;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int SPEECH_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 200;
    private static final int PERMISSION_REQUEST_LOCATION = 300;

    private TextView outputTextView;
    private GoogleMap map;
    private LatLng startPoint;
    private LatLng endPoint;
    private Polyline polyline;
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Hide the ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialize the map fragment
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map_fragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        Button startVoiceButton = findViewById(R.id.button_start_voice);
        outputTextView = findViewById(R.id.text_output);

        // Check and request microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_RECORD_AUDIO);
        }

        // Start speech recognition on button click
        startVoiceButton.setOnClickListener(view -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition();
            } else {
                outputTextView.setText("Microphone permission denied.");
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;

        // Check and request location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
            getCurrentLocation();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_LOCATION);
        }
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                        startPoint = currentLatLng;
                        map.addMarker(new MarkerOptions().position(currentLatLng).title("You are here"));
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f));
                    } else {
                        Log.d("Location", "No location found.");
                    }
                });
    }

    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } else {
            outputTextView.setText("No speech recognition app available.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spokenText = results.get(0);
                callDialogflow(spokenText);
            }
        }
    }

    private void callDialogflow(String query) {
        try {
            // Load Dialogflow credentials
            InputStream stream = getResources().openRawResource(R.raw.dialogflow_credentials);
            GoogleCredentials credentials = GoogleCredentials.fromStream(stream)
                    .createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));

            // Configure Dialogflow session settings
            SessionsSettings sessionsSettings = SessionsSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

            // Create Dialogflow session
            SessionsClient sessionsClient = SessionsClient.create(sessionsSettings);
            String sessionId = UUID.randomUUID().toString();
            SessionName session = SessionName.of("instapilau-deliveries", sessionId); // Replace with your project ID

            Log.d("Dialogflow", "Session ID: " + sessionId);

            // Build query input
            TextInput.Builder textInput = TextInput.newBuilder().setText(query).setLanguageCode("en");
            QueryInput queryInput = QueryInput.newBuilder().setText(textInput).build();

            // Create DetectIntentRequest
            DetectIntentRequest detectIntentRequest = DetectIntentRequest.newBuilder()
                    .setSession(session.toString())
                    .setQueryInput(queryInput)
                    .build();

            Log.d("Dialogflow", "Query Input: " + query);

            // Perform Dialogflow API call
            DetectIntentResponse response = sessionsClient.detectIntent(detectIntentRequest);

            // Get bot reply
            String botReply = response.getQueryResult().getFulfillmentText();
            Log.d("Dialogflow", "Bot Reply: " + botReply);

            // Handle response on UI thread
            runOnUiThread(() -> {
                // Modify the default response here
                String modifiedBotReply = botReply;

                // Check if the response includes a location intent, and modify the text accordingly
                if (response.getQueryResult().getIntent().getDisplayName().equals("Navigate")) {
                    String location = response.getQueryResult().getParameters()
                            .getFieldsMap()
                            .get("geo-city")
                            .getStringValue();

                    Log.d("Dialogflow", "Navigate to: " + location);

                    // Modify the default response message
                    modifiedBotReply = "I’m guiding you to " + location + " now!";

                    // Call the navigation method with the location
                    navigateToLocation(location);
                }

                // Display the modified bot reply
                outputTextView.setText(modifiedBotReply);
            });

        } catch (IOException e) {
            Log.e("Dialogflow", "IO Exception: Unable to load credentials or communicate with Dialogflow", e);
            runOnUiThread(() -> outputTextView.setText("Error: Unable to load Dialogflow credentials."));
        } catch (Exception e) {
            Log.e("Dialogflow", "Unexpected error occurred", e);
            runOnUiThread(() -> outputTextView.setText("Error communicating with Dialogflow."));
        }
    }

    private void navigateToLocation(String locationName) {
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocationName(locationName, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                LatLng latLng = new LatLng(address.getLatitude(), address.getLongitude());

                // Log the address and coordinates
                Log.d("Geocoder", "Location found: " + locationName + " at " + latLng);

                if (startPoint == null) {
                    startPoint = latLng;
                } else {
                    endPoint = latLng;
                    // Adding the marker to the map for the target location
                    map.addMarker(new MarkerOptions().position(latLng).title(locationName));
                    Log.d("Map", "Marker added at: " + latLng);

                    // Move camera to the destination
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f)); // Adjust zoom level as needed

                    // Draw line between the start and end point
                    drawLineBetweenPoints();
                }
            } else {
                Log.d("Geocoder", "Location not found: " + locationName);
                runOnUiThread(() -> Toast.makeText(this, "Unable to locate " + locationName, Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e("Geocoder", "Error retrieving location", e);
        }
    }

    private void drawLineBetweenPoints() {
        if (startPoint != null && endPoint != null) {
            // Draw a polyline between the start and end points
            if (polyline != null) {
                polyline.remove(); // Remove any existing polyline
            }

            polyline = map.addPolyline(new PolylineOptions()
                    .add(startPoint, endPoint)
                    .width(5)
                    .color(android.graphics.Color.BLUE));

            Log.d("Map", "Polyline drawn between start and end points.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition();
            } else {
                outputTextView.setText("Microphone permission is required.");
            }
        } else if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                map.setMyLocationEnabled(true);
                getCurrentLocation();
            } else {
                outputTextView.setText("Location permission is required.");
            }
        }
    }
}
