/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (props, at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.openplans.rcavl;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class GpsService extends Service {

	private GpsServiceThread thread;
	private LocalBinder binder = new LocalBinder();
	private RCAVL activity;
	private int pingInterval;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		String url = intent.getStringExtra("pingUrl");
		String email = intent.getStringExtra("email");
		String password = intent.getStringExtra("password");
		pingInterval = intent.getIntExtra("pingInterval", 60);
		

		Notification notification = new Notification(R.drawable.icon, "Ridepilot Mobile", System.currentTimeMillis());
		Intent appIntent = new Intent(this, RCAVL.class);
		
		appIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
	
		PendingIntent pi = PendingIntent.getActivity(this, 0, appIntent, 0);
		
		notification.setLatestEventInfo(this, "Ridepilot Mobile", "connected", pi);
		notification.flags|=Notification.FLAG_NO_CLEAR;
		startForeground(66786, notification);
		
		thread = new GpsServiceThread(url, email, password);
		new Thread(thread).start();
		return START_REDELIVER_INTENT;
	}

	@Override
	public void onDestroy() {
		stopForeground(true);
		thread.stop();
	}

	public class LocalBinder extends Binder {
		GpsService getService() {
			// Return this instance of LocalService so clients can call public
			// methods
			return GpsService.this;
		}
	}
	
	class GpsServiceThread implements LocationListener, Runnable {

		private static final int MAX_RETRIES = 3;
		private static final long SHORT_RETRY_TIME = 1000 * 10; //10 seconds
		private static final long LONG_RETRY_TIME = 1000 * 60; // 1 minute

		private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

		private final String TAG = GpsServiceThread.class.toString();
		private String url;
		private String password;
		private String email;
		private String status;
		private volatile boolean active;
		private Location lastLocation;
		private LocationManager locationManager;

		private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
		
		private Runnable pingTask = new Runnable() {
	        public void run() {
	        	if (active) {
	        		ping(false);
	        	}
	        }
	    };
	    private Runnable forcePingTask = new Runnable() {
	        public void run() {
	        	ping(true);
	        }
	    };
		public GpsServiceThread(String url, String email, String password) {
			this.url = url;
			this.email = email;
			this.password = password;
			active = true;
			scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
			setStatus("active");
		}

		public void stop() {
			scheduledThreadPoolExecutor.shutdown();
		}

		public void onLocationChanged(Location location) {
			if (!active) {
				return;
			}
			lastLocation = location;
		}

		private void ping(boolean keepTrying) {
			String pingStatus = this.status;

			String localDate;
			
			localDate = dateFormat.format(new Date());

			Location location = lastLocation;
			for (int i = 0; i < MAX_RETRIES || keepTrying; ++i) {
				HttpClient client = HttpUtils.getNewHttpClient();
				HttpPost request = new HttpPost(url);
				try {
					List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(6);
					nameValuePairs.add(new BasicNameValuePair(
							"user[email]", email));
					nameValuePairs.add(new BasicNameValuePair(
							"user[password]", password));

					if (location != null) {
						nameValuePairs.add(new BasicNameValuePair(
								"device_pool_driver[lat]",
								Double.toString(location.getLatitude())));
						nameValuePairs.add(new BasicNameValuePair(
								"device_pool_driver[lng]",
								Double.toString(location.getLongitude())));
					}
					nameValuePairs.add(new BasicNameValuePair(
							"device_pool_driver[status]",
							pingStatus));

					nameValuePairs.add(new BasicNameValuePair(
							"device_pool_driver[posted_at]", localDate));

					request.setEntity(new UrlEncodedFormEntity(
							nameValuePairs));

					HttpResponse response = client.execute(request);

					if (response.getStatusLine().getStatusCode() == 200) {
						HttpEntity entity = response.getEntity();
						String json = EntityUtils.toString(entity);
						JSONTokener tokener = new JSONTokener(json);
						JSONObject data = (JSONObject) tokener.nextValue();
						if (data.has("device_pool_driver")) {
							activity.ping();
							return; //success!
						}
						Log.e(TAG, "data was " + data);
						Log.e(TAG, "json was " + json);
					}
				} catch (ClientProtocolException e) {
					Log.e(TAG, "exception sending ping " + e);
				} catch (IOException e) {
					Log.e(TAG, "exception sending ping " + e);
				} catch (JSONException e) {
					Log.e(TAG, "bad json from server " + e);
				} catch (Exception e) {
					Log.e(TAG, "some other problem " + e);
				}
				try {
					if (i >= MAX_RETRIES && keepTrying) {
						Thread.sleep(LONG_RETRY_TIME);
					} else {
						Thread.sleep(SHORT_RETRY_TIME);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			Log.e(TAG, "ran out of retries contacting server");
			activity.ping();
			return;

		}

		public void onProviderDisabled(String provider) {
			Log.d(TAG, "provider disabled " + provider);
		}

		public void onProviderEnabled(String provider) {
			Log.d(TAG, "provider enabled " + provider);
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			Log.d(TAG, "on status changed " + provider + " status = " + status);
		}

		public void toast(String message) {
			if (!active) {
				return;
			}
			if (activity != null) {
				activity.toast(message);
			}
		}

		public void run() {
			new Thread (new Runnable() {
				public void run() {
					scheduledThreadPoolExecutor.scheduleAtFixedRate(pingTask, 0, pingInterval, TimeUnit.SECONDS);
				}
			} ).start();
			
			Looper.prepare();
			
			locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
			locationManager.requestLocationUpdates(
					LocationManager.GPS_PROVIDER, 1000, 10, this);

		}

		public void setStatus(String status) {
			this.status = status;
			scheduledThreadPoolExecutor.execute(forcePingTask);
		}

		public String getStatus() {
			return status;
		}

		public void setActive(boolean active) {
			this.active = active;
			if (active) {
				locationManager.requestLocationUpdates(
						LocationManager.GPS_PROVIDER, 0, 0, this);
			} else {
				locationManager.removeUpdates(this);
			}
		}

		public boolean isActive() {
			return active;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	public void setActivity(RCAVL activity) {
		this.activity = activity;
	}

	public void setStatus(String status, boolean active) {
		thread.setActive(active);
		thread.setStatus(status);
	}

	public boolean isActive() {
		return thread.isActive();
	}

}
