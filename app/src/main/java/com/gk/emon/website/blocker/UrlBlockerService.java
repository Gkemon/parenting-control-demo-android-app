package com.gk.emon.website.blocker;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

import static androidx.core.accessibilityservice.AccessibilityServiceInfoCompat.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
import static androidx.core.accessibilityservice.AccessibilityServiceInfoCompat.FLAG_REPORT_VIEW_IDS;
import static androidx.core.accessibilityservice.AccessibilityServiceInfoCompat.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
import static androidx.core.accessibilityservice.AccessibilityServiceInfoCompat.FLAG_REQUEST_FILTER_KEY_EVENTS;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Browser;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UrlBlockerService extends AccessibilityService {
    private final HashMap<String, Long> previousUrlDetections = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.packageNames = packageNames();
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
        //throttling of accessibility event notification
        info.notificationTimeout = 300;
        //support ids interception
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY |
                FLAG_REQUEST_FILTER_KEY_EVENTS |
                FLAG_REPORT_VIEW_IDS |
                FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                FLAG_ENABLE_ACCESSIBILITY_VOLUME |
                FLAG_REQUEST_ACCESSIBILITY_BUTTON |
                FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK |
                FLAG_INPUT_METHOD_EDITOR;

        this.setServiceInfo(info);
    }

    private String captureUrl(AccessibilityNodeInfo info, SupportedBrowserConfig config) {
        List<AccessibilityNodeInfo> nodes = info.findAccessibilityNodeInfosByViewId(config.addressBarId);
        if (nodes == null || nodes.size() == 0) {
            return null;
        }

        AccessibilityNodeInfo addressBarNodeInfo = nodes.get(0);
        String url = null;
        if (addressBarNodeInfo.getText() != null) {
            url = addressBarNodeInfo.getText().toString();
        }
        addressBarNodeInfo.recycle();
        return url;
    }

    @Override
    public void onAccessibilityEvent(@NonNull AccessibilityEvent event) {
        AccessibilityNodeInfo parentNodeInfo = event.getSource();
        if (parentNodeInfo == null) {
            return;
        }

        String packageName = event.getPackageName().toString();
        SupportedBrowserConfig browserConfig = null;
        for (SupportedBrowserConfig supportedConfig : getSupportedBrowsers()) {
            if (supportedConfig.packageName.equals(packageName)) {
                browserConfig = supportedConfig;
            }
        }
        //this is not supported browser, so exit
        if (browserConfig == null) {
            return;
        }

        String capturedUrl = captureUrl(parentNodeInfo, browserConfig);
        parentNodeInfo.recycle();

        //we can't find a url. Browser either was updated or opened page without url text field
        if (capturedUrl == null) {
            return;
        }

        long eventTime = event.getEventTime();
        String detectionId = packageName + ", and url " + capturedUrl;
        //noinspection ConstantConditions
        long lastRecordedTime = previousUrlDetections.containsKey(detectionId) ? previousUrlDetections.get(detectionId) : 0;
        //some kind of redirect throttling
        if (eventTime - lastRecordedTime > 2000) {
            previousUrlDetections.put(detectionId, eventTime);
            analyzeCapturedUrl(capturedUrl, browserConfig.packageName);
        }
    }

    private void analyzeCapturedUrl(@NonNull String capturedUrl, @NonNull String browserPackage) {
        String redirectUrl = "https://stackoverflow.com/";
        if (capturedUrl.contains("facebook.com")) {
            performRedirect(redirectUrl, browserPackage);
        }
    }

    /**
     * we just reopen the browser app with our redirect url using service context
     * We may use more complicated solution with invisible activity to send a simple intent to open the url
     */
    private void performRedirect(@NonNull String redirectUrl, @NonNull String browserPackage) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl));
            intent.setPackage(browserPackage);
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, browserPackage);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // the expected browser is not installed
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl));
            startActivity(i);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @NonNull
    private static String[] packageNames() {
        List<String> packageNames = new ArrayList<>();
        for (SupportedBrowserConfig config : getSupportedBrowsers()) {
            packageNames.add(config.packageName);
        }
        return packageNames.toArray(new String[0]);
    }

    private static class SupportedBrowserConfig {
        public String packageName, addressBarId;

        public SupportedBrowserConfig(String packageName, String addressBarId) {
            this.packageName = packageName;
            this.addressBarId = addressBarId;
        }
    }

    /**
     * @return a list of supported browser configs
     * This list could be instead obtained from remote server to support future browser updates without updating an app
     */
    @NonNull
    private static List<SupportedBrowserConfig> getSupportedBrowsers() {
        List<SupportedBrowserConfig> browsers = new ArrayList<>();

        browsers.add(new SupportedBrowserConfig("com.android.chrome", "com.android.chrome:id/url_bar"));
        browsers.add(new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/url_bar_title"));
        browsers.add(new SupportedBrowserConfig("com.opera.mini.native", "com.opera.mini.native:id/url_field"));
        browsers.add(new SupportedBrowserConfig("com.safaribrowser", "com.safaribrowser:id/etUrl"));
        browsers.add(new SupportedBrowserConfig("com.microsoft.emmx", "com.microsoft.emmx:id/location_bar"));

        return browsers;
    }

}
