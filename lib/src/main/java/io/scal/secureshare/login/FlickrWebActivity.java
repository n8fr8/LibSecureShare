package io.scal.secureshare.login;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import info.guardianproject.netcipher.web.WebkitProxy;
import timber.log.Timber;

import io.scal.secureshare.lib.Util;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class FlickrWebActivity extends LockableActivity {
    private static final String TAG = "FlickrWebActivity";
    // FIXME security: we need to override the webviews cache, cookies, formdata cache to store only in sqlcipher/iocipher, currently it hits disk and then we clear it
    private WebView mWebview; 
    
    @SuppressLint("SetJavaScriptEnabled")
	public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Timber.d("onCreate()");
                
        String url = null;
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            url = extras.getString("authURL");
        }
        
        if ((url == null) || (url.length() == 0)) {
            Timber.e("no url found");
            return;
        }

        // check for tor settings and set proxy
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        boolean useTor = settings.getBoolean("pusetor", false);

        mWebview = new WebView(this);

        if (useTor) {
            Timber.d("user selected \"use tor\"");

            if ((!OrbotHelper.isOrbotInstalled(getApplicationContext())) || (!OrbotHelper.isOrbotRunning(getApplicationContext()))) {
                Timber.e("user selected \"use tor\" but orbot is not installed or not running");
                return;
            } else {
                try {
                    WebkitProxy.setProxy("android.app.Application", getApplicationContext(), mWebview, Util.ORBOT_HOST, Util.ORBOT_HTTP_PORT);
                } catch (Exception e) {
                    Timber.e("user selected \"use tor\" but an exception was thrown while setting the proxy: " + e.getLocalizedMessage());
                    return;
                }
            }
        } else {
            Timber.d("user selected \"don't use tor\"");
        }
                
        mWebview.getSettings().setJavaScriptEnabled(true);
        mWebview.setVisibility(View.VISIBLE);

		setContentView(mWebview);
        
		mWebview.setWebViewClient(new WebViewClient() {
            Intent resultIntent = new Intent();

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("oauth_token=") && url.contains("oauth_verifier=")) {
                    Uri uri = Uri.parse(url);
                    String token = uri.getQueryParameter("oauth_token");
                    String verifier = uri.getQueryParameter("oauth_verifier");
                    resultIntent.putExtra("token", token);
                    resultIntent.putExtra("verifier", verifier);
                    setResult(Activity.RESULT_OK, resultIntent);
                    finish();
                    return true;
                } else {
                    return super.shouldOverrideUrlLoading(view, url);
                }
            }
        });
        
		mWebview.loadUrl(url);
    }
    
	@Override
	public void finish() {
		Timber.d("finish()");
		
		super.finish();
		Util.clearWebviewAndCookies(mWebview, this);	
	}
}
