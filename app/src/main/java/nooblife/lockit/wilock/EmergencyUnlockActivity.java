package nooblife.lockit.wilock;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.preference.PreferenceManager;

public class EmergencyUnlockActivity extends Activity {

    TextView emergencyUnlockCode;
    CardView completionButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_unlock);
        emergencyUnlockCode = findViewById(R.id.emergency_unlock_code);
        emergencyUnlockCode.setText(PreferenceManager.getDefaultSharedPreferences(this).getString(Util.PREF_EMERGENCY_UNLOCK_CODE, "0000"));
        completionButton = findViewById(R.id.completion_button);
        completionButton.setOnClickListener(view ->
                new AlertDialog.Builder(EmergencyUnlockActivity.this)
                        .setTitle("Confirmation Required")
                        .setMessage("If your TV is unlocked after following these steps, tap YES to reset the app")
                        .setNegativeButton("NO", (dialogInterface, i) -> finish())
                        .setPositiveButton("YES", (dialogInterface, i) -> {
                            setResult(RESULT_OK);
                            finish();
                        }).create().show());
    }
}
