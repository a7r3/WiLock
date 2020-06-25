package nooblife.lockit.wilock;

import android.content.Context;
import android.widget.Toast;

public class Util {

    public static void showToast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static final String LOCKIT_SERVICE_ID = "com.n00blife.lockit.ACTION_LOCKIT_CONNECTIVITY";
    public static final String LOCKIT_DISCOVERY_SERVICE_ID = "LOCKIT";
    public static final String PREF_LOCKIT_RC_SERVICE_ID = "rcserviceid";
    public static final String LOCKIT_SERVICE_TYPE = "_lockit._tcp";

}
