package uk.co.abgroup.siteaudit;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * AB Site Audit - Android wrapper.
 * The whole app is the HTML file in assets/index.html, shown full screen in a WebView.
 * This class gives it what a plain browser page cannot have: camera and microphone,
 * the phone's file picker (Files, OneDrive, Gallery), saving straight into
 * Downloads / Pictures, opening and sharing PDFs, and the phone's own voice typing.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String FOLDER = "AB Site Audit";

    private static final int REQ_FILE = 1;
    private static final int REQ_PERM_CAMERA_WEB = 2;
    private static final int REQ_PERM_CAMERA_INTENT = 3;
    private static final int REQ_PERM_AUDIO = 4;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private PermissionRequest pendingWebPermission;

    private SpeechRecognizer speech;
    private boolean speechWanted = false;
    private String speechLang = "en-GB";
    private final Handler ui = new Handler(Looper.getMainLooper());

    // one file save at a time, written in chunks from JavaScript
    private OutputStream saveOut;
    private Uri saveUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B2E4F"));

        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Bridge(), "ABNative");
        web.loadUrl(START_URL);
    }

    // ---------------------------------------------------------------- browser features

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                boolean wantsCamera = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                }
                if (!wantsCamera) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingWebPermission = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA_WEB);
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            if (params.isCaptureEnabled()) {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA_INTENT);
                } else {
                    launchCamera();
                }
            } else {
                launchPicker(params);
            }
            return true;
        }
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "captures");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            cameraUri = null;
            cancelFileChooser();
            toast("Camera not available");
        }
    }

    private void launchPicker(WebChromeClient.FileChooserParams params) {
        cameraUri = null;
        List<String> mimes = new ArrayList<>();
        boolean anything = false;
        String[] accept = params.getAcceptTypes();
        if (accept != null) {
            for (String raw : accept) {
                for (String a : raw.split(",")) {
                    a = a.trim().toLowerCase();
                    if (a.isEmpty()) continue;
                    if (a.equals(".pdf")) a = "application/pdf";
                    if (a.contains("json") || a.startsWith(".")) { anything = true; continue; }
                    if (!mimes.contains(a)) mimes.add(a);
                }
            }
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (anything || mimes.isEmpty()) {
            i.setType("*/*");
        } else if (mimes.size() == 1) {
            i.setType(mimes.get(0));
        } else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose file"), REQ_FILE);
        } catch (Exception e) {
            cancelFileChooser();
        }
    }

    private void cancelFileChooser() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (cameraUri != null && (data == null || data.getData() == null)) {
                result = new Uri[]{cameraUri};
            } else if (data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_PERM_CAMERA_WEB && pendingWebPermission != null) {
            if (granted) pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        } else if (requestCode == REQ_PERM_CAMERA_INTENT) {
            if (granted) launchCamera(); else cancelFileChooser();
        } else if (requestCode == REQ_PERM_AUDIO) {
            if (granted) startListening();
            else { speechWanted = false; speechEvent("error", "permission"); }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (speech != null) { speech.destroy(); speech = null; }
        super.onDestroy();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------- voice typing

    private Intent speechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return i;
    }

    private void startListening() {
        if (!speechWanted) return;
        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this);
            speech.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { speechEvent("start", ""); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] bytes) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int t, Bundle b) { }

                @Override
                public void onPartialResults(Bundle b) {
                    String t = first(b);
                    if (t != null && !t.isEmpty()) speechEvent("partial", t);
                }

                @Override
                public void onResults(Bundle b) {
                    String t = first(b);
                    if (t != null && !t.isEmpty()) speechEvent("final", t);
                    restartSoon();
                }

                @Override
                public void onError(int error) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        restartSoon();
                    } else {
                        speechWanted = false;
                        speechEvent("error", String.valueOf(error));
                    }
                }
            });
        }
        try {
            speech.startListening(speechIntent());
        } catch (Exception e) {
            speechWanted = false;
            speechEvent("error", "start");
        }
    }

    private void restartSoon() {
        if (speechWanted) ui.postDelayed(this::startListening, 150);
        else speechEvent("end", "");
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty()) ? null : r.get(0);
    }

    private void speechEvent(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            final String js = "window.__abSpeech&&window.__abSpeech(" + o + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- JavaScript bridge

    private class Bridge {
        @JavascriptInterface
        public boolean isNative() { return true; }

        @JavascriptInterface
        public String version() { return "1.0"; }

        /** Start saving a file. kind "photo" goes to Pictures/AB Site Audit (shows in Gallery), anything else to Downloads/AB Site Audit. */
        @JavascriptInterface
        public synchronized String saveStart(String name, String mime, String kind) {
            try {
                closeQuietly();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection;
                if ("photo".equals(kind)) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
                    collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                } else {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                    collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                }
                saveUri = getContentResolver().insert(collection, cv);
                if (saveUri == null) return "error:could not create file";
                saveOut = getContentResolver().openOutputStream(saveUri);
                return saveOut == null ? "error:could not open file" : "ok";
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String saveChunk(String base64) {
            try {
                if (saveOut == null) return "error:no file open";
                saveOut.write(Base64.decode(base64, Base64.DEFAULT));
                return "ok";
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        /** Finish the save; returns the file's content:// address, or "error:..." */
        @JavascriptInterface
        public synchronized String saveEnd() {
            try {
                if (saveOut == null || saveUri == null) return "error:no file open";
                saveOut.flush();
                saveOut.close();
                saveOut = null;
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(saveUri, cv, null, null);
                String u = saveUri.toString();
                saveUri = null;
                return u;
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void openUri(String uri, String mime) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(uri), mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Open with"));
                } catch (Exception e) {
                    toast("No app found to open this file");
                }
            });
        }

        @JavascriptInterface
        public void shareUri(String uri, String mime, String title) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
                    i.putExtra(Intent.EXTRA_SUBJECT, title);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share"));
                } catch (Exception e) {
                    toast("Could not share the file");
                }
            });
        }

        @JavascriptInterface
        public boolean speechAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void startSpeech(String lang) {
            runOnUiThread(() -> {
                speechLang = (lang == null || lang.isEmpty()) ? "en-GB" : lang;
                speechWanted = true;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening();
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_AUDIO);
                }
            });
        }

        @JavascriptInterface
        public void stopSpeech() {
            runOnUiThread(() -> {
                speechWanted = false;
                if (speech != null) {
                    try { speech.stopListening(); } catch (Exception ignored) { }
                }
            });
        }
    }

    private void closeQuietly() {
        try { if (saveOut != null) saveOut.close(); } catch (Exception ignored) { }
        saveOut = null;
        saveUri = null;
    }
}
