package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.util.Consumer;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OsmdroidMapsProvider implements IMapsProvider {

    private static final String ATTRIBUTION = "© OpenStreetMap contributors";
    private static boolean configurationApplied;

    public OsmdroidMapsProvider() {
        initializeMaps(null);
    }

    @Override
    public void initializeMaps(Context context) {
        if (configurationApplied) {
            return;
        }
        configurationApplied = true;
        String versionName = "";
        try {
            PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            versionName = packageInfo.versionName;
        } catch (Exception e) {
            FileLog.e(e);
        }
        IConfigurationProvider configuration = Configuration.getInstance();
        configuration.setUserAgentValue("Telegram-FOSS(F-Droid) " + versionName);
        configuration.setOsmdroidBasePath(new File(ApplicationLoader.applicationContext.getCacheDir(), "osmdroid"));
        configuration.setOsmdroidTileCache(new File(ApplicationLoader.applicationContext.getCacheDir(), "osmdroid/tiles"));
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        return new OsmdroidMapView(context);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new OsmdroidCameraUpdate(new GeoPoint(latLng.latitude, latLng.longitude), null, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new OsmdroidCameraUpdate(new GeoPoint(latLng.latitude, latLng.longitude), (double) zoom, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new OsmdroidCameraUpdate(null, null, ((OsmdroidLatLngBounds) bounds).boundingBox, padding);
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new OsmdroidLatLngBoundsBuilder();
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new IMapStyleOptions() {};
    }

    @Override
    public String getMapsAppPackageName() {
        return "com.google.android.apps.maps";
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new OsmdroidMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new OsmdroidCircleOptions();
    }

    public final static class OsmdroidMapView implements IMapView {
        private final MapView mapView;
        private final Context context;
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;
        private Runnable onLayoutListener;

        private OsmdroidMapView(Context context) {
            this.context = context;
            mapView = new MapView(context) {
                private Paint attributionPaint;
                private Paint attributionBackgroundPaint;
                private float attributionWidth;

                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (dispatchInterceptor != null) {
                        return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (interceptInterceptor != null) {
                        return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
                    }
                    return super.onInterceptTouchEvent(ev);
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (onLayoutListener != null) {
                        onLayoutListener.run();
                    }
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    super.dispatchDraw(canvas);
                    if (attributionPaint == null) {
                        attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        attributionPaint.setTextSize(AndroidUtilities.dp(10));
                        attributionPaint.setColor(0xff1a1a1a);
                        attributionWidth = attributionPaint.measureText(ATTRIBUTION);
                        attributionBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        attributionBackgroundPaint.setColor(0x99ffffff);
                    }
                    float right = getWidth() - AndroidUtilities.dp(4);
                    float bottom = getHeight() - AndroidUtilities.dp(2);
                    RectF rect = new RectF(right - attributionWidth - AndroidUtilities.dp(6), bottom - AndroidUtilities.dp(14), right, bottom);
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), attributionBackgroundPaint);
                    canvas.drawText(ATTRIBUTION, rect.left + AndroidUtilities.dp(3), bottom - AndroidUtilities.dp(4), attributionPaint);
                }
            };
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setMultiTouchControls(true);
            mapView.setBuiltInZoomControls(false);
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            callback.accept(new OsmdroidMapImpl(mapView, context));
        }

        @Override
        public void onResume() {
            mapView.onResume();
        }

        @Override
        public void onPause() {
            mapView.onPause();
        }

        @Override
        public void onCreate(Bundle savedInstance) {}

        @Override
        public void onDestroy() {
            mapView.onDetach();
        }

        @Override
        public void onLowMemory() {}

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            dispatchInterceptor = touchInterceptor;
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            interceptInterceptor = touchInterceptor;
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            onLayoutListener = callback;
        }
    }

    public final static class OsmdroidMapImpl implements IMap {
        private final MapView mapView;
        private final Context context;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Map<Marker, OsmdroidMarker> implToAbsMarkerMap = new HashMap<>();
        private final Map<Marker, Object> markerTags = new HashMap<>();
        private MyLocationNewOverlay myLocationOverlay;
        private Consumer<Location> myLocationCallback;
        private OnMarkerClickListener markerClickListener;
        private Runnable cameraIdleListener;
        private Runnable cameraMoveListener;
        private OnCameraMoveStartedListener cameraMoveStartedListener;
        private boolean gestureStarted;
        private final Runnable cameraIdleRunnable = () -> {
            gestureStarted = false;
            if (cameraIdleListener != null) {
                cameraIdleListener.run();
            }
        };

        private OsmdroidMapImpl(MapView mapView, Context context) {
            this.mapView = mapView;
            this.context = context;
            mapView.addMapListener(new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    onMapMoved();
                    return false;
                }

                @Override
                public boolean onZoom(ZoomEvent event) {
                    onMapMoved();
                    return false;
                }
            });
        }

        private void onMapMoved() {
            if (cameraMoveStartedListener != null && !gestureStarted) {
                gestureStarted = true;
                cameraMoveStartedListener.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_GESTURE);
            }
            if (cameraMoveListener != null) {
                cameraMoveListener.run();
            }
            if (cameraIdleListener != null) {
                handler.removeCallbacks(cameraIdleRunnable);
                handler.postDelayed(cameraIdleRunnable, 300);
            }
        }

        private void applyCameraUpdate(ICameraUpdate update, boolean animated) {
            if (!(update instanceof OsmdroidCameraUpdate)) {
                return;
            }
            OsmdroidCameraUpdate cameraUpdate = (OsmdroidCameraUpdate) update;
            if (cameraUpdate.bounds != null) {
                zoomToBoundingBoxSafe(cameraUpdate.bounds, animated, cameraUpdate.boundsPadding);
            } else if (cameraUpdate.point != null) {
                if (animated) {
                    mapView.getController().animateTo(cameraUpdate.point);
                } else {
                    mapView.getController().setCenter(cameraUpdate.point);
                }
                if (cameraUpdate.zoom != null) {
                    mapView.getController().setZoom(cameraUpdate.zoom);
                }
            }
        }

        private void zoomToBoundingBoxSafe(BoundingBox bounds, boolean animated, int border) {
            int width = mapView.getWidth();
            int height = mapView.getHeight();
            if (!mapView.isLayoutOccurred() || width <= 0 || height <= 0) {
                mapView.addOnFirstLayoutListener((v, left, top, right, bottom) -> zoomToBoundingBoxSafe(bounds, animated, border));
                return;
            }
            int clampedBorder = Math.min(border, Math.max(0, (Math.min(width, height) - 1) / 2));
            try {
                mapView.zoomToBoundingBox(bounds, animated, clampedBorder);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        @Override
        public void setMapType(int mapType) {}

        @Override
        public void animateCamera(ICameraUpdate update) {
            applyCameraUpdate(update, true);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            applyCameraUpdate(update, true);
            if (callback != null) {
                handler.post(callback::onFinish);
            }
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            applyCameraUpdate(update, true);
            if (callback != null) {
                handler.postDelayed(callback::onFinish, duration);
            }
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            applyCameraUpdate(update, false);
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) mapView.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) mapView.getMinZoomLevel();
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            if (enabled) {
                if (myLocationOverlay != null) {
                    myLocationOverlay.enableMyLocation();
                    return;
                }
                GpsMyLocationProvider locationProvider = new GpsMyLocationProvider(context);
                locationProvider.setLocationUpdateMinDistance(10);
                locationProvider.setLocationUpdateMinTime(10000);
                locationProvider.addLocationSource(LocationManager.NETWORK_PROVIDER);
                myLocationOverlay = new MyLocationNewOverlay(locationProvider, mapView) {
                    @Override
                    public void onLocationChanged(Location location, IMyLocationProvider source) {
                        super.onLocationChanged(location, source);
                        if (location != null && myLocationCallback != null) {
                            Location current = location;
                            AndroidUtilities.runOnUIThread(() -> myLocationCallback.accept(current));
                        }
                    }
                };
                myLocationOverlay.enableMyLocation();
                myLocationOverlay.setDrawAccuracyEnabled(true);
                mapView.getOverlayManager().add(myLocationOverlay);
            } else if (myLocationOverlay != null) {
                myLocationOverlay.disableMyLocation();
            }
        }

        @Override
        public IUISettings getUiSettings() {
            return new OsmdroidUISettings(mapView);
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            cameraIdleListener = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener onCameraMoveStartedListener) {
            cameraMoveStartedListener = onCameraMoveStartedListener;
        }

        @Override
        public CameraPosition getCameraPosition() {
            org.osmdroid.api.IGeoPoint center = mapView.getMapCenter();
            return new CameraPosition(new LatLng(center.getLatitude(), center.getLongitude()), (float) mapView.getZoomLevelDouble());
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            mapView.post(callback);
        }

        @Override
        public IProjection getProjection() {
            return new OsmdroidProjection(mapView.getProjection());
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            mapView.setPadding(left, top, right, bottom);
        }

        @Override
        public void setMapStyle(IMapStyleOptions style) {}

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            OsmdroidMarkerOptions options = (OsmdroidMarkerOptions) markerOptions;
            Marker marker = new Marker(mapView);
            if (options.position != null) {
                marker.setPosition(options.position);
            }
            if (options.bitmap != null) {
                marker.setIcon(new BitmapDrawable(context.getResources(), options.bitmap));
            } else if (options.resId != 0) {
                Drawable drawable = context.getDrawable(options.resId);
                if (drawable != null) {
                    marker.setIcon(drawable);
                }
            }
            marker.setAnchor(options.anchorU, options.anchorV);
            marker.setTitle(options.title);
            marker.setSnippet(options.snippet);
            marker.setOnMarkerClickListener((m, view) -> {
                OsmdroidMarker abs = implToAbsMarkerMap.get(m);
                if (abs == null) {
                    abs = new OsmdroidMarker(m);
                    implToAbsMarkerMap.put(m, abs);
                }
                return markerClickListener != null && markerClickListener.onClick(abs);
            });
            mapView.getOverlayManager().add(marker);
            OsmdroidMarker abs = new OsmdroidMarker(marker);
            implToAbsMarkerMap.put(marker, abs);
            return abs;
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            myLocationCallback = callback;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener markerClickListener) {
            this.markerClickListener = markerClickListener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            cameraMoveListener = callback;
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            OsmdroidCircleOptions options = (OsmdroidCircleOptions) circleOptions;
            Polygon polygon = new Polygon();
            polygon.getOutlinePaint().setColor(options.strokeColor);
            polygon.getFillPaint().setColor(options.fillColor);
            polygon.getOutlinePaint().setStrokeWidth(AndroidUtilities.dp(options.strokeWidth));
            if (options.center != null) {
                polygon.setPoints(Polygon.pointsAsCircle(options.center, options.radius));
            }
            mapView.getOverlayManager().add(polygon);
            return new OsmdroidCircle(mapView, polygon, options.center, options.radius);
        }

        public final class OsmdroidMarker implements IMarker {
            private final Marker marker;

            private OsmdroidMarker(Marker marker) {
                this.marker = marker;
            }

            @Override
            public Object getTag() {
                return markerTags.get(marker);
            }

            @Override
            public void setTag(Object tag) {
                if (tag == null) {
                    markerTags.remove(marker);
                } else {
                    markerTags.put(marker, tag);
                }
            }

            @Override
            public LatLng getPosition() {
                org.osmdroid.api.IGeoPoint position = marker.getPosition();
                return new LatLng(position.getLatitude(), position.getLongitude());
            }

            @Override
            public void setPosition(LatLng latLng) {
                marker.setPosition(new GeoPoint(latLng.latitude, latLng.longitude));
            }

            @Override
            public void setRotation(int rotation) {
                marker.setRotation((float) rotation);
            }

            @Override
            public void setIcon(Bitmap bitmap) {
                marker.setIcon(new BitmapDrawable(context.getResources(), bitmap));
            }

            @Override
            public void setIcon(int resId) {
                Drawable drawable = context.getDrawable(resId);
                if (drawable != null) {
                    marker.setIcon(drawable);
                }
            }

            @Override
            public void remove() {
                mapView.getOverlayManager().remove(marker);
                implToAbsMarkerMap.remove(marker);
                markerTags.remove(marker);
            }
        }
    }

    public final static class OsmdroidCircle implements ICircle {
        private final MapView mapView;
        private final Polygon polygon;
        private GeoPoint center;
        private double radius;

        private OsmdroidCircle(MapView mapView, Polygon polygon, GeoPoint center, double radius) {
            this.mapView = mapView;
            this.polygon = polygon;
            this.center = center;
            this.radius = radius;
        }

        @Override
        public void setStrokeColor(int color) {
            polygon.getOutlinePaint().setColor(color);
        }

        @Override
        public void setFillColor(int color) {
            polygon.getFillPaint().setColor(color);
        }

        @Override
        public void setRadius(double radius) {
            this.radius = radius;
            if (center != null) {
                polygon.setPoints(Polygon.pointsAsCircle(center, radius));
            }
        }

        @Override
        public double getRadius() {
            return radius;
        }

        @Override
        public void setCenter(LatLng latLng) {
            center = new GeoPoint(latLng.latitude, latLng.longitude);
            polygon.setPoints(Polygon.pointsAsCircle(center, radius));
        }

        @Override
        public void remove() {
            mapView.getOverlayManager().remove(polygon);
        }
    }

    public final static class OsmdroidProjection implements IProjection {
        private final Projection projection;

        private OsmdroidProjection(Projection projection) {
            this.projection = projection;
        }

        @Override
        public Point toScreenLocation(LatLng latLng) {
            return projection.toPixels(new GeoPoint(latLng.latitude, latLng.longitude), null);
        }
    }

    public final static class OsmdroidUISettings implements IUISettings {
        private final MapView mapView;

        private OsmdroidUISettings(MapView mapView) {
            this.mapView = mapView;
        }

        @Override
        public void setMyLocationButtonEnabled(boolean enabled) {}

        @Override
        public void setZoomControlsEnabled(boolean enabled) {
            mapView.setBuiltInZoomControls(false);
        }

        @Override
        public void setCompassEnabled(boolean enabled) {}
    }

    public final static class OsmdroidCircleOptions implements ICircleOptions {
        private GeoPoint center;
        private double radius;
        private int strokeColor = Color.BLACK;
        private int fillColor = Color.TRANSPARENT;
        private int strokeWidth = 2;

        @Override
        public ICircleOptions center(LatLng latLng) {
            center = new GeoPoint(latLng.latitude, latLng.longitude);
            return this;
        }

        @Override
        public ICircleOptions radius(double radius) {
            this.radius = radius;
            return this;
        }

        @Override
        public ICircleOptions strokeColor(int color) {
            strokeColor = color;
            return this;
        }

        @Override
        public ICircleOptions fillColor(int color) {
            fillColor = color;
            return this;
        }

        @Override
        public ICircleOptions strokePattern(List<PatternItem> patternItems) {
            return this;
        }

        @Override
        public ICircleOptions strokeWidth(int width) {
            strokeWidth = width;
            return this;
        }
    }

    public final static class OsmdroidMarkerOptions implements IMarkerOptions {
        private GeoPoint position;
        private Bitmap bitmap;
        private int resId;
        private float anchorU = 0.5f;
        private float anchorV = 1.0f;
        private String title;
        private String snippet;

        @Override
        public IMarkerOptions position(LatLng latLng) {
            position = new GeoPoint(latLng.latitude, latLng.longitude);
            return this;
        }

        @Override
        public IMarkerOptions icon(Bitmap bitmap) {
            this.bitmap = bitmap;
            return this;
        }

        @Override
        public IMarkerOptions icon(int resId) {
            this.resId = resId;
            return this;
        }

        @Override
        public IMarkerOptions anchor(float lat, float lng) {
            anchorU = lat;
            anchorV = lng;
            return this;
        }

        @Override
        public IMarkerOptions title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public IMarkerOptions snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }

        @Override
        public IMarkerOptions flat(boolean flat) {
            return this;
        }
    }

    public final static class OsmdroidLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private double minLatitude = Double.MAX_VALUE;
        private double maxLatitude = -Double.MAX_VALUE;
        private double minLongitude = Double.MAX_VALUE;
        private double maxLongitude = -Double.MAX_VALUE;

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            minLatitude = Math.min(minLatitude, latLng.latitude);
            maxLatitude = Math.max(maxLatitude, latLng.latitude);
            minLongitude = Math.min(minLongitude, latLng.longitude);
            maxLongitude = Math.max(maxLongitude, latLng.longitude);
            return this;
        }

        @Override
        public ILatLngBounds build() {
            return new OsmdroidLatLngBounds(new BoundingBox(maxLatitude, maxLongitude, minLatitude, minLongitude));
        }
    }

    public final static class OsmdroidLatLngBounds implements ILatLngBounds {
        private final BoundingBox boundingBox;

        private OsmdroidLatLngBounds(BoundingBox boundingBox) {
            this.boundingBox = boundingBox;
        }

        @Override
        public LatLng getCenter() {
            double latitude = (boundingBox.getLatNorth() + boundingBox.getLatSouth()) / 2.0;
            double longitude = (boundingBox.getLonEast() + boundingBox.getLonWest()) / 2.0;
            return new LatLng(latitude, longitude);
        }
    }

    public final static class OsmdroidCameraUpdate implements ICameraUpdate {
        private final GeoPoint point;
        private final Double zoom;
        private final BoundingBox bounds;
        private final int boundsPadding;

        private OsmdroidCameraUpdate(GeoPoint point, Double zoom, BoundingBox bounds, int boundsPadding) {
            this.point = point;
            this.zoom = zoom;
            this.bounds = bounds;
            this.boundsPadding = boundsPadding;
        }
    }
}
