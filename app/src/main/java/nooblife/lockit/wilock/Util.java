package nooblife.lockit.wilock;

import android.content.Context;
import android.graphics.Color;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class Util {

    public static void showToast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static int giveMeRed(Context context) {
        return ContextCompat.getColor(context, R.color.red_500);
    }

    public static int giveMeGreen(Context context) {
        return ContextCompat.getColor(context, R.color.green_500);
    }

    public static void resetApp(Context context) {
        Util.showToast(context, "App reset to normal! Pair with your TV again to start Lock");
        setPrefEmergencyUnlockCode(context, "0000");
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear().apply();
    }

    public static void setPrefEmergencyUnlockCode(Context context, String code) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(Util.PREF_EMERGENCY_UNLOCK_CODE, code)
                .apply();
    }

    public static final String PREF_LOCKIT_RC_SERVICE_ID = "rcserviceid";
    public static final String LOCKIT_DEFAULT_SERVICE_ID = "lockit";
    public static final String PREF_EMERGENCY_UNLOCK_CODE = "nooblife.wilock.pref.EMERGENCY_UNLOCK_CODE";
    public static final String LOCKIT_SERVICE_TEMPLATE = "_%s._tcp";
    public static final String PREF_LOCKIT_TV_NAME = "tvname";
}
