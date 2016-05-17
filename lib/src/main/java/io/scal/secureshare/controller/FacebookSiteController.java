
package io.scal.secureshare.controller;

import timber.log.Timber;

import io.scal.secureshare.login.FacebookLoginActivity;
import io.scal.secureshare.model.Account;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;

import com.facebook.HttpMethod;
import com.facebook.Request;
import com.facebook.Response;
import com.facebook.Session;
import com.facebook.model.GraphUser;

import org.json.JSONException;
import org.json.JSONObject;

public class FacebookSiteController extends SiteController {
    public static final String SITE_NAME = "Facebook";
    public static final String SITE_KEY = "facebook";
    private static final String TAG = "FacebookSiteController";

    public static final String PHOTO_SET_KEY = "PhotoSetPaths";
    public static final String POST_NOT_PUBLIC = "PostNotPublic";

    private String postId = null;
    private String title = null;
    private String album = null;
    private ArrayList<String> photosToUpload = null;
    private int pendingUploads = 0;

    public FacebookSiteController(Context context, Handler handler, String jobId) {
        super(context, handler, jobId);
        // TODO Auto-generated constructor stub
    }

    @Override
    public void startAuthentication(Account account) {
        Intent intent = new Intent(mContext, FacebookLoginActivity.class);
        intent.putExtra(SiteController.EXTRAS_KEY_CREDENTIALS, account.getCredentials());
        ((Activity) mContext).startActivityForResult(intent, SiteController.CONTROLLER_REQUEST_CODE); // FIXME not a safe cast, context might be a service
    }

    @Override
    public void upload(Account account, HashMap<String, String> valueMap) {
		Timber.d("Upload file: Entering upload");
		
		title = valueMap.get(VALUE_KEY_TITLE);
		String body = valueMap.get(VALUE_KEY_BODY);

        // check for photo set upload paths
        String mediaPath = "";
        if (valueMap.keySet().contains(PHOTO_SET_KEY)) {
            mediaPath = valueMap.get(PHOTO_SET_KEY);
        } else {
            mediaPath = valueMap.get(VALUE_KEY_MEDIA_PATH);
        }

		boolean useTor = (valueMap.get(VALUE_KEY_USE_TOR).equals("true")) ? true : false;
        Session session = Session.openActiveSessionFromCache(mContext);

        // setup callback
        Request.Callback uploadMediaRequestCallback = new Request.OnProgressCallback() {
            @Override
            public void onCompleted(Response response) {

                // privacy check callback
                // ckeck to see if a video post is public
                Request.Callback privacyCheckRequestCallback = new Request.Callback() {
                    @Override
                    public void onCompleted(Response response) {

                        Timber.d("PRIVACY CHECK RESPONSE: " + response.toString());

                        // request will fail unless app has user_videos permission
                        // since this feature is incomplete, just continue
                        if (response.getError() != null) {

                            Timber.e("PRIVACY CHECK, ERROR: " + response.getError() + " (IGNORED)");
                            jobSucceeded(postId);
                            return;
                        }

                        JSONObject graphResponse = response.getGraphObject().getInnerJSONObject();

                        try {

                            String privacyValue = graphResponse.getString("privacy");

                            Timber.d("PRIVACY CHECK, FOUND: " + privacyValue);

                            if ((privacyValue != null) && (privacyValue.equals("everyone"))) {

                                // post is public, ok to publish
                                jobSucceeded(postId);

                            } else {

                                // post is not public, need to handle this somehow
                                jobSucceeded(POST_NOT_PUBLIC);

                            }

                        } catch (JSONException je) {

                            Timber.e("FAILED TO EXTRACT PRIVACY SETTINGS FROM RESPONSE: " + je.getMessage() + " (IGNORED)");

                            // currently unable to find privacy field in video responses, so i'm letting this through
                            jobSucceeded(postId);

                        }
                    }
                };


                // post fail
                if (response.getError() != null) {
                    Timber.d("media upload problem. Error= " + response.getError());
                    jobFailed(null, 1, response.getError().toString());
                    return;
                }

                Object graphResponse = response.getGraphObject().getProperty("id");

                // upload fail
                if (graphResponse == null || !(graphResponse instanceof String)
                        || TextUtils.isEmpty((String) graphResponse)) {
                    Timber.d("failed media upload/no response");

                    jobFailed(null, 0, "failed media upload/no response");
                }
                // upload success
                else {

                    postId = (String) graphResponse;

                    Timber.d("LOOKING FOR POST ID " + postId);

                    Session session = Session.openActiveSessionFromCache(mContext);
                    Bundle parameters = null;
                    Request request = new Request(session, postId, parameters, HttpMethod.GET, privacyCheckRequestCallback);

                    request.executeAsync();
                }
            }

            @Override
            public void onProgress(long current, long max) {
                float percent = ((float) current) / ((float) max);
                jobProgress(percent, "Facebook uploading...");
            }
        };

        // photo album callback
        // ckeck to see if album already exists
        /*
        Request.Callback checkAlbumRequestCallback = new Request.Callback() {
            @Override
            public void onCompleted(Response response) {

                Timber.d("ALBUM CHECK RESPONSE: " + response.toString());

                JSONObject graphResponse = response.getGraphObject().getInnerJSONObject();

                try {
                    JSONArray albumArray = graphResponse.getJSONArray("data");

                    for (int i = 0; i < albumArray.length(); i++) {

                        JSONObject albumObject = albumArray.getJSONObject(i);

                        Timber.d("ALBUM CHECK, FOUND: " + albumObject.getString("name") + "/" + albumObject.getString("id"));
                    }

                } catch (JSONException je) {

                    Timber.e("FAILED TO EXTRACT ALBUM LIST FROM RESPONSE: " + je.getMessage());

                    jobFailed(null, 0, "An error occurred while creating the album");

                }
            }
        };
        */

        // photo album callback
        Request.Callback uploadAlbumRequestCallback = new Request.OnProgressCallback() {
            @Override
            public void onCompleted(Response response) {

                Timber.d("ALBUM CREATION RESPONSE: " + response.toString());

                JSONObject graphResponse = response.getGraphObject().getInnerJSONObject();

                try {

                    album = graphResponse.getString("id");

                    Timber.d("NEW ALBUM ID: " + album);

                    // photo callback
                    Request.Callback uploadPhotoRequestCallback = new Request.OnProgressCallback() {
                        @Override
                        public void onCompleted(Response response) {

                            Timber.d("PHOTO UPLOAD RESPONSE: " + response.toString());

                            JSONObject graphResponse = response.getGraphObject().getInnerJSONObject();

                            try {

                                Timber.d("NEW PHOTO ID: " + graphResponse.getString("id"));

                                pendingUploads--;

                                // privacy check callback
                                // ckeck to see if an album post is public
                                Request.Callback privacyCheckRequestCallback = new Request.Callback() {
                                    @Override
                                    public void onCompleted(Response response) {

                                        Timber.d("PRIVACY CHECK RESPONSE: " + response.toString());

                                        JSONObject graphResponse = response.getGraphObject().getInnerJSONObject();

                                        try {

                                            String privacyValue = graphResponse.getString("privacy");

                                            Timber.d("PRIVACY CHECK, FOUND: " + privacyValue);

                                            if ((privacyValue != null) && (privacyValue.equals("everyone"))) {

                                                // post is public, ok to publish
                                                jobSucceeded(album);

                                            } else {

                                                // post is not public, need to handle this somehow
                                                jobSucceeded(POST_NOT_PUBLIC);

                                            }



                                        } catch (JSONException je) {

                                            Timber.e("FAILED TO EXTRACT PRIVACY SETTINGS FROM RESPONSE: " + je.getMessage());

                                            // could not determine privacy setting, assume the worst
                                            jobSucceeded(POST_NOT_PUBLIC);

                                        }
                                    }
                                };

                                if (pendingUploads == 0) {

                                    Timber.d("ALL UPLOADS COMPLETE");

                                    Session session = Session.openActiveSessionFromCache(mContext);
                                    Bundle parameters = null;
                                    Request request = new Request(session, album, parameters, HttpMethod.GET, privacyCheckRequestCallback);

                                    request.executeAsync();

                                    // jobSucceeded(album);

                                } else {

                                    Timber.d(pendingUploads + " UPLOADS REMAINING");

                                }

                            } catch (JSONException je) {

                                Timber.e("FAILED TO EXTRACT PHOTO ID FROM RESPONSE: " + je.getMessage());

                                jobFailed(null, 0, "An error occurred while uploading the photo");

                            }
                        }

                        @Override
                        public void onProgress(long current, long max) {
                            float percent = ((float) current) / ((float) max);
                            jobProgress(percent, "uploading photo...");
                        }
                    };

                    // upload photo to album

                    Timber.d("PHOTO UPLOAD");

                    pendingUploads = photosToUpload.size();

                    int photoNumber = 1;

                    for (String photoToUpload : photosToUpload) {

                        try {
                            FileInputStream inStream = new FileInputStream(photoToUpload);

                            ByteArrayOutputStream outStream = new ByteArrayOutputStream();

                            byte[] photoBuffer = new byte[1024];

                            for (int i; (i = inStream.read(photoBuffer)) != -1; ) {
                                outStream.write(photoBuffer, 0, i);
                            }

                            byte[] photoBytes = outStream.toByteArray();

                            Session session = Session.openActiveSessionFromCache(mContext);
                            Bundle parameters = null;
                            Request request = new Request(session, album + "/photos", parameters, HttpMethod.POST, uploadPhotoRequestCallback);

                            //Timber.d("GOT REQUEST (" + photoNumber + ")");

                            parameters = request.getParameters();
                            parameters.putByteArray("source", photoBytes);
                            parameters.putString("name", title + "_" + photoNumber);

                            //Timber.d("GOT PARAMETERS (" + photoNumber + ")");

                            //Timber.d("EXECUTING REQUEST (" + photoNumber + ")...");

                            request.executeAsync();

                        } catch (FileNotFoundException fnfe) {
                            // can't find photo file
                            Timber.e("COULD NOT FIND FILE " + photoToUpload + " -> " + fnfe.getMessage());
                        } catch (IOException ioe) {
                            // can't read photo file
                            Timber.e("COULD NOT READ FILE " + photoToUpload + " -> " + ioe.getMessage());
                        }

                        photoNumber++;

                    }

                    // TEMP
                    // jobSucceeded(album);

                } catch (JSONException je) {

                    Timber.e("FAILED TO EXTRACT ALBUM ID FROM RESPONSE: " + je.getMessage());

                    jobFailed(null, 0, "An error occurred while creating the album");

                }
            }

            @Override
            public void onProgress(long current, long max) {
                float percent = ((float) current) / ((float) max);
                jobProgress(percent, "creating album...");
            }
        };

        Bundle parameters = null;
        Request request = null;

        Timber.d("MEDIA PATH: " + mediaPath);

        if (mediaPath.contains(";")) {
            // upload multiple photos

            Timber.d("MULTIPLE FILE UPLOAD");

            String[] photoPaths = mediaPath.split(";");

            photosToUpload = new ArrayList<String>();
            for (int i = 0; i < photoPaths.length; i++) {
               photosToUpload.add(photoPaths[i]);
            }

            // doing this to hit the photos api
            // may restructure to check for existing album
            // Request requestTwo = null;
            // requestTwo = new Request(session, "me/albums", parameters, HttpMethod.GET, checkAlbumRequestCallback);
            // requestTwo.executeAsync();

            request = new Request(session, "me/albums", parameters, HttpMethod.POST, uploadAlbumRequestCallback);

            //Timber.d("GOT REQUEST");

            parameters = request.getParameters();
            parameters.putString("name", title);

            //Timber.d("GOT PARAMETERS");

        } else {
            // upload single photo or video

            Timber.d("SINGLE FILE UPLOAD");

            File mediaFile = new File(mediaPath);
            try {
                if (super.isVideoFile(mediaFile)) {
                    request = Request.newUploadVideoRequest(session, mediaFile, uploadMediaRequestCallback);

                    if (torCheck(useTor, mContext)) {
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ORBOT_HOST, ORBOT_HTTP_PORT));
                        request.setProxy(proxy);
                    }

                    parameters = request.getParameters();

                    //video params
                    parameters.putString("title", title);
                    parameters.putString("description", body);
                } else if (super.isImageFile(mediaFile)) {
                    request = Request.newUploadPhotoRequest(session, mediaFile, uploadMediaRequestCallback);

                    if (torCheck(useTor, mContext)) {
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ORBOT_HOST, ORBOT_HTTP_PORT));
                        request.setProxy(proxy);
                    }

                    parameters = request.getParameters();

                    //image params
                    parameters.putString("name", title);
                } else {
                    Timber.d("media type not supported");
                    return;
                }

                request.setParameters(parameters);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        //Timber.d("EXECUTING REQUEST...");

        request.executeAsync();
    }

    static String userId; // FIXME we should be caching this at login
    public static String getUserId(){
        final Session session = Session.getActiveSession();
        if (session != null && session.isOpened()) {
            Request request = Request.newMeRequest(session, new Request.GraphUserCallback() {
                @Override
                public void onCompleted(GraphUser user, Response response) {
                    if (session == Session.getActiveSession()) {
                        if (user != null) {
                            userId = user.getId();
                        }
                    }
                }
            });
            Request.executeAndWait(request);
            return userId;
        }else{
            return null;
        }

    }

    @Override
    public void startMetadataActivity(Intent intent) {
        return; // nop
    }

}
