// Copyright 2015-present 650 Industries. All rights reserved.

package host.exp.exponent.gcm;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import com.google.android.gms.gcm.GcmListenerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;
import host.exp.exponent.Constants;
import host.exp.exponent.ExponentManifest;
import host.exp.exponent.LauncherActivity;
import host.exp.exponent.RNObject;
import host.exp.exponent.analytics.EXL;
import host.exp.exponent.storage.ExperienceDBObject;
import host.exp.exponent.storage.ExponentDB;
import host.exp.exponent.storage.ExponentSharedPreferences;
import host.exp.exponent.utils.ColorParser;
import host.exp.exponentview.Exponent;

import host.exp.exponentview.R;

public class ExponentGcmListenerService extends GcmListenerService {

  public static class ExponentPushNotification {
    public final String experienceId;
    public final String body;
    public final int notificationId;
    public final boolean isMultiple;

    public ExponentPushNotification(final String experienceId, final String body, final int notificationId, final boolean isMultiple) {
      this.experienceId = experienceId;
      this.body = body;
      this.notificationId = notificationId;
      this.isMultiple = isMultiple;
    }

    public static ExponentPushNotification fromJSONObjectString(final String json) {
      if (json == null) {
        return null;
      }

      try {
        JSONObject object = new JSONObject(json);
        return new ExponentPushNotification(object.getString(NOTIFICATION_EXPERIENCE_ID_KEY), object.getString(NOTIFICATION_MESSAGE_KEY), object.getInt(NOTIFICATION_ID_KEY), object.getBoolean(NOTIFICATION_IS_MULTIPLE_KEY));
      } catch (JSONException e) {
        EXL.e(TAG, e.toString());
        return null;
      }
    }

    public JSONObject toJSONObject(String origin) {
      JSONObject notification = new JSONObject();
      try {
        notification.put(NOTIFICATION_EXPERIENCE_ID_KEY, experienceId);
        if (origin != null) {
          notification.put(NOTIFICATION_ORIGIN_KEY, origin);
        }
        notification.put(NOTIFICATION_MESSAGE_KEY, body); // deprecated
        notification.put(NOTIFICATION_DATA_KEY, body);
        notification.put(NOTIFICATION_ID_KEY, notificationId);
        notification.put(NOTIFICATION_IS_MULTIPLE_KEY, isMultiple);
      } catch (JSONException e) {
        EXL.e(TAG, e.toString());
      }

      return notification;
    }

    public Object toWriteableMap(String sdkVersion, String origin) {
      RNObject args = new RNObject("com.facebook.react.bridge.Arguments").loadVersion(sdkVersion).callStaticRecursive("createMap");
      if (origin != null) {
        args.call("putString", NOTIFICATION_ORIGIN_KEY, origin);
      }
      args.call("putString", NOTIFICATION_DATA_KEY, body);
      args.call("putInt", NOTIFICATION_ID_KEY, notificationId);
      args.call("putBoolean", NOTIFICATION_IS_MULTIPLE_KEY, isMultiple);
      return args.get();
    }
  }

  public static class ReceivedPushNotificationEvent extends ExponentPushNotification {

    public ReceivedPushNotificationEvent(String experienceId, String body, int notificationId, boolean isMultiple) {
      super(experienceId, body, notificationId, isMultiple);
    }
  }

  private static final String TAG = ExponentGcmListenerService.class.getSimpleName();
  private static final int MAX_COLLAPSED_NOTIFICATIONS = 5;
  private static final String NOTIFICATION_MESSAGE_KEY = "message"; // deprecated
  private static final String NOTIFICATION_EXPERIENCE_ID_KEY = "experienceId";
  private static final String NOTIFICATION_DATA_KEY = "data";
  private static final String NOTIFICATION_ORIGIN_KEY = "origin";
  private static final String NOTIFICATION_ID_KEY = "notificationId";
  private static final String NOTIFICATION_IS_MULTIPLE_KEY = "isMultiple";
  private static final String NOTIFICATION_COLLAPSE_MODE = "collapse";
  private static final String NOTIFICATION_UNREAD_COUNT_KEY = "#{unread_notifications}";

  private static ExponentGcmListenerService sInstance;
  public static ExponentGcmListenerService getInstance() {
    return sInstance;
  }

  private enum Mode {
    DEFAULT,
    COLLAPSE
  }

  @Inject
  ExponentManifest mExponentManifest;

  @Inject
  ExponentSharedPreferences mExponentSharedPreferences;

  @Override
  public void onCreate() {
    super.onCreate();
    Exponent.di().inject(this);

    sInstance = this;
  }

  @Override
  public void onMessageReceived(String from, Bundle bundle) {
    final String body = bundle.getString("body");

    final String experienceId = bundle.getString("experienceId");
    if (experienceId == null) {
      EXL.e(TAG, "No experienceId in push payload.");
      return;
    }

    final String message = bundle.getString("message");
    if (message == null) {
      EXL.e(TAG, "No message in push payload.");
      return;
    }

    ExponentDB.experienceIdToExperience(experienceId, new ExponentDB.ExperienceResultListener() {
      @Override
      public void onSuccess(ExperienceDBObject experience) {
        try {
          JSONObject manifest = new JSONObject(experience.manifest);
          sendNotification(message, experienceId, experience.manifestUrl, manifest, body);
        } catch (JSONException e) {
          EXL.e(TAG, "Couldn't deserialize JSON for experience id " + experienceId);
        }
      }

      @Override
      public void onFailure() {
        EXL.e(TAG, "No experience found for id " + experienceId);
      }
    });
  }

  private void sendNotification(final String message, final String experienceId, final String manifestUrl,
                                final JSONObject manifest, final String body) {
    final String name = manifest.optString(ExponentManifest.MANIFEST_NAME_KEY);
    if (name == null) {
      EXL.e(TAG, "No name found for experience id " + experienceId);
      return;
    }

    final JSONObject notificationPreferences = manifest.optJSONObject(ExponentManifest.MANIFEST_NOTIFICATION_INFO_KEY);

    // Icon
    String iconUrl = manifest.optString(ExponentManifest.MANIFEST_ICON_URL_KEY);
    if (notificationPreferences != null) {
      iconUrl = notificationPreferences.optString(ExponentManifest.MANIFEST_NOTIFICATION_ICON_URL_KEY, null);
    }

    mExponentManifest.loadIconBitmap(iconUrl, new ExponentManifest.BitmapListener() {
      @Override
      public void onLoadBitmap(Bitmap bitmap) {
        Mode mode = Mode.DEFAULT;
        String collapsedTitle = null;
        JSONArray unreadNotifications = new JSONArray();

        // Modes
        if (notificationPreferences != null) {
          String modeString = notificationPreferences.optString(ExponentManifest.MANIFEST_NOTIFICATION_ANDROID_MODE);
          if (NOTIFICATION_COLLAPSE_MODE.equals(modeString)) {
            mode = Mode.COLLAPSE;
          }
        }

        // Update metadata
        int notificationId = mode == Mode.COLLAPSE ? experienceId.hashCode() : new Random().nextInt();
        addUnreadNotificationToMetadata(experienceId, message, notificationId);

        // Collapse mode fields
        if (mode == Mode.COLLAPSE) {
          unreadNotifications = getUnreadNotificationsFromMetadata(experienceId);

          String collapsedTitleRaw = notificationPreferences.optString(ExponentManifest.MANIFEST_NOTIFICATION_ANDROID_COLLAPSED_TITLE);
          if (collapsedTitleRaw != null) {
            collapsedTitle = collapsedTitleRaw.replace(NOTIFICATION_UNREAD_COUNT_KEY, "" + unreadNotifications.length());
          }
        }

        // Color
        int color;
        String colorString = notificationPreferences == null ? null :
            notificationPreferences.optString(ExponentManifest.MANIFEST_NOTIFICATION_COLOR_KEY);
        if (colorString != null && ColorParser.isValid(colorString)) {
          color = Color.parseColor(colorString);
        } else {
          color = mExponentManifest.getColorFromManifest(manifest);
        }

        // Create notification object
        boolean isMultiple = mode == Mode.COLLAPSE && unreadNotifications.length() > 1;
        ReceivedPushNotificationEvent notificationEvent = new ReceivedPushNotificationEvent(experienceId, body, notificationId, isMultiple);

        // Create pending intent
        Intent intent = new Intent(ExponentGcmListenerService.this, LauncherActivity.class);
        intent.putExtra(LauncherActivity.MANIFEST_URL_KEY, manifestUrl);
        intent.putExtra(LauncherActivity.NOTIFICATION_KEY, body); // deprecated
        intent.putExtra(LauncherActivity.NOTIFICATION_OBJECT_KEY, notificationEvent.toJSONObject(null).toString());
        PendingIntent pendingIntent = PendingIntent.getActivity(ExponentGcmListenerService.this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT);

        // Build notification
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder;

        if (isMultiple) {
          NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
              .setBigContentTitle(collapsedTitle);

          for (int i = 0; i < Math.min(unreadNotifications.length(), MAX_COLLAPSED_NOTIFICATIONS); i++) {
            try {
              JSONObject unreadNotification = (JSONObject) unreadNotifications.get(i);
              style.addLine(unreadNotification.getString(NOTIFICATION_MESSAGE_KEY));
            } catch (JSONException e) {
              e.printStackTrace();
            }
          }

          if (unreadNotifications.length() > MAX_COLLAPSED_NOTIFICATIONS) {
            style.addLine("and " + (unreadNotifications.length() - MAX_COLLAPSED_NOTIFICATIONS) + " more...");
          }

          notificationBuilder = new NotificationCompat.Builder(ExponentGcmListenerService.this)
              .setSmallIcon(R.drawable.notification_icon)
              .setContentTitle(collapsedTitle)
              .setColor(color)
              .setContentText(name)
              .setAutoCancel(true)
              .setSound(defaultSoundUri)
              .setContentIntent(pendingIntent)
              .setStyle(style);
        } else {
          notificationBuilder = new NotificationCompat.Builder(ExponentGcmListenerService.this)
              .setSmallIcon(R.drawable.notification_icon)
              .setContentTitle(name)
              .setColor(color)
              .setContentText(message)
              .setAutoCancel(true)
              .setSound(defaultSoundUri)
              .setContentIntent(pendingIntent);
        }

        // Add icon
        Notification notification;
        if (!manifestUrl.equals(Constants.INITIAL_URL)) {
          notification = notificationBuilder.setLargeIcon(bitmap).build();
        } else {
          // TODO: don't actually need to load bitmap in this case
          notification = notificationBuilder.setSmallIcon(R.drawable.shell_notification_icon).build();
        }

        // Display
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(ExponentGcmListenerService.this);
        notificationManager.notify(notificationId, notification);

        // Send event. Will be consumed if experience is already open.
        EventBus.getDefault().post(notificationEvent);
      }
    });
  }

  private void addUnreadNotificationToMetadata(String experienceId, String message, int notificationId) {
    try {
      JSONObject notification = new JSONObject();
      notification.put(NOTIFICATION_MESSAGE_KEY, message);
      notification.put(NOTIFICATION_ID_KEY, notificationId);

      JSONObject metadata = mExponentSharedPreferences.getExperienceMetadata(experienceId);
      if (metadata == null) {
        metadata = new JSONObject();
      }

      JSONArray unreadNotifications = metadata.optJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_NOTIFICATIONS);
      if (unreadNotifications == null) {
        unreadNotifications = new JSONArray();
      }

      unreadNotifications.put(notification);

      metadata.put(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_NOTIFICATIONS, unreadNotifications);
      mExponentSharedPreferences.updateExperienceMetadata(experienceId, metadata);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  private JSONArray getUnreadNotificationsFromMetadata(String experienceId) {
    JSONObject metadata = mExponentSharedPreferences.getExperienceMetadata(experienceId);
    if (metadata != null) {
      if (metadata.has(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_NOTIFICATIONS)) {
        try {
          return metadata.getJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_NOTIFICATIONS);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    }

    return new JSONArray();
  }

  public void removeNotifications(JSONArray unreadNotifications) {
    if (unreadNotifications == null) {
      return;
    }

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
    for (int i = 0; i < unreadNotifications.length(); i++) {
      try {
        notificationManager.cancel(Integer.parseInt(((JSONObject) unreadNotifications.get(i)).getString(NOTIFICATION_ID_KEY)));
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
  }
}