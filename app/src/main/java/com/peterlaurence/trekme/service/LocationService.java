package com.peterlaurence.trekme.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.peterlaurence.trekme.MainActivity;
import com.peterlaurence.trekme.R;
import com.peterlaurence.trekme.core.TrekMeContext;
import com.peterlaurence.trekme.core.track.TrackStatCalculator;
import com.peterlaurence.trekme.core.track.TrackStatistics;
import com.peterlaurence.trekme.ui.events.RecordGpxStopEvent;
import com.peterlaurence.trekme.service.event.GpxFileWriteEvent;
import com.peterlaurence.trekme.service.event.LocationServiceStatus;
import com.peterlaurence.trekme.util.gpx.GPXWriter;
import com.peterlaurence.trekme.util.gpx.model.Gpx;
import com.peterlaurence.trekme.util.gpx.model.Track;
import com.peterlaurence.trekme.util.gpx.model.TrackPoint;
import com.peterlaurence.trekme.util.gpx.model.TrackSegment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A <a href="https://developer.android.com/guide/components/services.html#CreatingAService">Started service</a>
 * to perform Gpx recordings, even if the user is not interacting with the main application. <br>
 * It is started in the foreground to avoid Android 8.0 (API lvl 26)
 * <a href="https://developer.android.com/about/versions/oreo/background.html">background
 * execution limits</a>. <br>
 * So when there is a Gpx recording, the user can always see it with the icon on the upper left
 * corner of the device.
 *
 * It uses the legacy location API in android.location, not the Google Location Services API, part
 * of Google Play Services. This is because we absolutely need to use only the {@link LocationManager#GPS_PROVIDER}.
 * The fused provider don't give us the hand on that.
 */
public class LocationService extends Service {
    private static final String GPX_VERSION = "1.1";
    private static final String NOTIFICATION_ID = "peterlaurence.LocationService";
    private static final int SERVICE_ID = 126585;
    private Looper mServiceLooper;
    private Handler mServiceHandler;
    private LocationManager mLocationManager;
    private LocationListener mLocationListener;
    private long locationCounter = 0;

    private List<TrackPoint> mTrackPoints;
    private TrackStatCalculator mTrackStatCalculator;

    private boolean mStarted = false;

    public LocationService() {
    }

    @Override
    public void onCreate() {
        EventBus.getDefault().register(this);

        /* Start up the thread running the service.  Note that we create a separate thread because
         * the service normally runs in the process's main thread, which we don't want to block.
         * We also make it background priority so CPU-intensive work will not disrupt our UI.
         */
        HandlerThread thread = new HandlerThread("LocationServiceThread",
                Thread.MIN_PRIORITY);
        thread.start();

        /* Get the HandlerThread's Looper and use it for our Handler */
        mServiceLooper = thread.getLooper();
        mServiceHandler = new Handler(mServiceLooper);

        mServiceHandler.handleMessage(new Message());

        /* Create the Gpx instance */
        mTrackPoints = new ArrayList<>();

        /* Prepare the stat calculator */
        mTrackStatCalculator = new TrackStatCalculator();

        mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        mLocationListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                locationCounter++;
                /* Drop points that aren't precise enough */
                if (location.getAccuracy() > 15) {
                    return;
                }

                /* Drop the first 3 points, so the GPS stabilizes */
                if (locationCounter <= 3) {
                    return;
                }

                mServiceHandler.post(() -> {
                    Double altitude = location.getAltitude() != 0.0 ? location.getAltitude() : null;
                    TrackPoint trackPoint = new TrackPoint(location.getLatitude(),
                            location.getLongitude(), altitude, location.getTime(), "");
                    mTrackPoints.add(trackPoint);
                    mTrackStatCalculator.addTrackPoint(trackPoint);
                    sendTrackStatistics(mTrackStatCalculator.getStatistics());
                });
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            public void onProviderEnabled(String provider) {
            }

            public void onProviderDisabled(String provider) {
            }
        };

        startLocationUpdates();
    }

    /**
     * When we stop recording the location events, create a {@link Gpx} object for further
     * serialization. <br>
     * Whatever the outcome of this process, a {@link GpxFileWriteEvent} is emitted in the
     * LocationServiceThread.
     */
    private void createGpx() {
        mServiceHandler.post(() -> {
            ArrayList<TrackSegment> trkSegList = new ArrayList<>();
            trkSegList.add(new TrackSegment(mTrackPoints));

            /* Name the track using the current date */
            Date date = new Date();
            DateFormat dateFormat = new SimpleDateFormat("dd\\MM\\yyyy-HH:mm:ss", Locale.ENGLISH);
            String trackName = "track-" + dateFormat.format(date);

            Track track = new Track(trkSegList, trackName);
            track.setStatistics(mTrackStatCalculator.getStatistics());

            ArrayList<Track> trkList = new ArrayList<>();
            trkList.add(track);

            ArrayList<TrackPoint> wayPoints = new ArrayList<>();

            Gpx gpx = new Gpx(trkList, wayPoints, TrekMeContext.INSTANCE.getAppFolderName(), GPX_VERSION);
            try {
                String gpxFileName = trackName + ".gpx";
                File gpxFile = new File(TrekMeContext.INSTANCE.getRecordingsDir(), gpxFileName);
                FileOutputStream fos = new FileOutputStream(gpxFile);
                GPXWriter.INSTANCE.write(gpx, fos);
            } catch (Exception e) {
                // for instance, don't care : we want to stop the service anyway
                // TODO : warn the user that the gpx file could not be saved
            } finally {
                EventBus.getDefault().post(new GpxFileWriteEvent());
            }
        });
    }

    /**
     * Called when the service is started.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.mipmap.ic_launcher);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getApplicationContext(), NOTIFICATION_ID)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.service_location_action))
                .setSmallIcon(R.drawable.ic_my_location_black_24dp)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            /* This is only needed on Devices on Android O and above */
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel mChannel = new NotificationChannel(NOTIFICATION_ID, getText(R.string.service_location_name), NotificationManager.IMPORTANCE_DEFAULT);
            mChannel.enableLights(true);
            mChannel.setLightColor(Color.MAGENTA);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(mChannel);
            }
            notificationBuilder.setChannelId(NOTIFICATION_ID);
        }
        Notification notification = notificationBuilder.build();

        startForeground(SERVICE_ID, notification);

        mStarted = true;
        sendStatus();

        return START_NOT_STICKY;
    }

    /**
     * Create and write a new gpx file. <br>
     * After this is done, a {@link GpxFileWriteEvent} is emitted through event bus so the service
     * can stop properly.
     */
    @Subscribe
    public void onRecordGpxStopEvent(RecordGpxStopEvent event) {
        createGpx();
    }

    /**
     * Self-respond to a {link GpxFileWriteEvent} emitted by the service. <br>
     * When a GPX file has just been written, stop the service and send the status.
     */
    @Subscribe
    public void onGpxFileWriteEvent(GpxFileWriteEvent event) {
        mStarted = false;
        sendStatus();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        mServiceLooper.quitSafely();
        EventBus.getDefault().unregister(this);
    }

    /**
     * Only use locations from the GPS.
     */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2, mLocationListener, mServiceLooper);
    }

    private void stopLocationUpdates() {
        mLocationManager.removeUpdates(mLocationListener);
    }

    /**
     * Send the started/stopped boolean status of the service.
     */
    private void sendStatus() {
        EventBus.getDefault().postSticky(new LocationServiceStatus(mStarted));
    }

    /**
     * Send updated track statistics.
     */
    private void sendTrackStatistics(TrackStatistics stats) {
        EventBus.getDefault().postSticky(stats);
    }
}
