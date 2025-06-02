package ai.kf.arkat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentOnAttachListener;

import com.google.ar.core.Anchor;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.Sceneform;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.InstructionsController;
import com.google.ar.sceneform.ux.TransformableNode;
import com.google.ar.sceneform.ux.VideoNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements
        FragmentOnAttachListener,
        Scene.OnUpdateListener {

    private final List<MediaPlayer> mediaPlayers = new ArrayList<>();
    private ArFragment arFragment;
    private boolean alreadyRendered = false;
    private static final String TAG = "DhonActivity";
    private int cardIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) toolbar.getLayoutParams();
                if (params != null) {
                    params.topMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    toolbar.setLayoutParams(params);
                }
                return WindowInsetsCompat.CONSUMED;
            });
        }

        getSupportFragmentManager().addFragmentOnAttachListener(this);

        if (savedInstanceState == null) {
            if (Sceneform.isSupported(this)) {
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.arFragment, ArFragment.class, null)
                        .commit();
            }
        }
    }

    @Override
    public void onAttachFragment(@NonNull FragmentManager fragmentManager, @NonNull Fragment fragment) {
        if (fragment.getId() == R.id.arFragment && fragment instanceof ArFragment) {
            arFragment = (ArFragment) fragment;
            Log.d(TAG, "onAttachFragment: Ar fragment attached");

            new Thread(() -> {
                try {
                    while (arFragment.getArSceneView() == null) {
                        Thread.sleep(500); // Wait for the AR scene view to be initialized
                    }
                    Log.d(TAG, "AR scene view initialized");
                    runOnUiThread(this::setupSession);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Error in thread sleep", e);
                }
            }).start();

        }
    }



    private void setupSession() {
        if (arFragment == null || arFragment.getArSceneView() == null) {
            Log.e(TAG, "AR components not initialized");
            return;
        }

        Session arSession = arFragment.getArSceneView().getSession();
        if (arSession == null) {
            Log.e(TAG, "AR session is null");
            return;
        }
        // Disable plane renderer
        setupPlaneRenderer(arFragment);

        try {
            // Load the image
            Bitmap bitmap;
            try (InputStream bitmapString = getAssets().open("card.jpg")) {
                bitmap = BitmapFactory.decodeStream(bitmapString);
                if (bitmap == null) {
                    throw new IOException("Failed to decode bitmap");
                }
            }

            // Create image database
            AugmentedImageDatabase imageDatabase = new AugmentedImageDatabase(arSession);
            float imageWidthInMeters = 0.13f; // 13 cm
            cardIndex = imageDatabase.addImage("card", bitmap, imageWidthInMeters);

            if (cardIndex == -1) {
                Log.e(TAG, "Failed to add image to database");
                return;
            }

            // Configure session
            Config config = new Config(arSession);
            //auto focus and other settings
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setAugmentedImageDatabase(imageDatabase);
            arFragment.setInstructionsController(null);
            arFragment.getArSceneView().getPlaneRenderer().setEnabled(false);
            arSession.configure(config);

            // Add update listener
            if (arFragment.getArSceneView().getScene() != null) {
                arFragment.getArSceneView().getScene().addOnUpdateListener(this);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error setting up AR session", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Error setting up AR: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onUpdate(FrameTime frameTime) {
        if (arFragment == null || arFragment.getArSceneView() == null) {
            return;
        }

        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null) return;

        try {
            Collection<AugmentedImage> updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage.class);
            for (AugmentedImage img : updatedAugmentedImages) {
                if (img.getTrackingState() == TrackingState.TRACKING) {
                    switch (img.getTrackingMethod()) {
                        case LAST_KNOWN_POSE:
                            Log.d(TAG, "Last known pose");
                            break;
                        case FULL_TRACKING:
                            Log.d(TAG, "Full tracking");
                            if (img.getIndex() == cardIndex) {
                                Log.d(TAG, "Card detected and fully tracked");
                                // Add your rendering code here
                                if (!alreadyRendered) {
                                    playVideo(img.createAnchor(img.getCenterPose()));
                                    alreadyRendered = true;
                                }
                            }
                            break;
                        case NOT_TRACKING:
                            Log.d(TAG, "Not tracking");
                            break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onUpdate", e);
        }
    }

    private void setupPlaneRenderer(ArFragment arFragment) {
        arFragment.getArSceneView().getPlaneRenderer().setEnabled(false);
        arFragment.getArSceneView().getPlaneRenderer().setVisible(false);
        arFragment.getArSceneView().getPlaneRenderer().setShadowReceiver(false);
    }
    // ... (rest of your lifecycle methods remain the same, but add null checks for mediaPlayers)

    private void playVideo(Anchor anchor){
        if (arFragment == null) {
            return;
        }
        if (arFragment.getArSceneView() == null) {
            return;
        }
        // Create the Anchor.
        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());
        anchorNode.setRenderable(null);

        // Create the transformable model and add it to the anchor.
        TransformableNode modelNode = new TransformableNode(arFragment.getTransformationSystem());
        modelNode.setParent(anchorNode);

        final int rawResId;
        final Color chromaKeyColor;
        rawResId = R.raw.oiia;
        chromaKeyColor = new Color(0.1843f, 1.0f, 0.098f);

        MediaPlayer player = MediaPlayer.create(this, rawResId);
        player.setLooping(true);
        player.start();
        mediaPlayers.add(player);
        VideoNode videoNode = new VideoNode(this, player, chromaKeyColor, new VideoNode.Listener() {
            @Override
            public void onCreated(VideoNode videoNode) {
                videoNode.setLocalScale(new Vector3(0.5f, 0.5f, 0.5f)); // 50% of original size
                videoNode.getRenderable().setShadowCaster(false);  // ✅ Disable shadow casting
                videoNode.getRenderable().setShadowReceiver(false);  // ✅ Disable receiving shadows
            }

            @Override
            public void onError(Throwable throwable) {
                Toast.makeText(MainActivity.this, "Unable to load material", Toast.LENGTH_LONG).show();
            }
        });
        videoNode.setParent(modelNode);

        // If you want that the VideoNode is always looking to the
        // Camera (You) comment the next line out. Use it mainly
        // if you want to display a Video. The use with activated
        // ChromaKey might look odd.
        //videoNode.setRotateAlwaysToCamera(true);

        modelNode.select();
    }

    @Override
    protected void onStop() {
        super.onStop();
        for (MediaPlayer mediaPlayer : mediaPlayers) {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.pause();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error pausing media player", e);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (MediaPlayer mediaPlayer : mediaPlayers) {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.reset();
                    mediaPlayer.release();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error releasing media player", e);
                }
            }
        }
        mediaPlayers.clear();
    }
}