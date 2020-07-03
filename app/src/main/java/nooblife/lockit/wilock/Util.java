package nooblife.lockit.wilock;

import android.content.Context;
import android.graphics.Color;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

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

    public static final String LOCKIT_SERVICE_ID = "com.n00blife.lockit.ACTION_LOCKIT_CONNECTIVITY";
    public static final String PREF_LOCKIT_RC_SERVICE_ID = "rcserviceid";
    public static final String PREF_LOCKIT_BONJOURSERVICE = "bonjourservice";
    public static final String LOCKIT_DEFAULT_SERVICE_ID = "lockit";
    public static final String LOCKIT_SERVICE_TEMPLATE = "_%s._tcp";
    public static final String PREF_LOCKIT_TV_NAME = "tvname";
}
