package edu.northwestern.cbits.purple_robot_manager;

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import edu.northwestern.cbits.purple_robot_manager.plugins.OutputPlugin;
import edu.northwestern.cbits.purple_robot_manager.probes.Probe;
import edu.northwestern.cbits.purple_robot_manager.triggers.DateTrigger;
import edu.northwestern.cbits.purple_robot_manager.triggers.Trigger;

public class ManagerService extends IntentService
{
	public static String PERIODIC_CHECK_INTENT = "purple_robot_manager_periodic_check";
	public static String INCOMING_DATA_INTENT = "purple_robot_manager_incoming_data";
	public static String APPLICATION_LAUNCH_INTENT = "purple_robot_manager_application_launch";
	public static String APPLICATION_LAUNCH_INTENT_PACKAGE = "purple_robot_manager_widget_launch_package";
	public static String APPLICATION_LAUNCH_INTENT_PARAMETERS = "purple_robot_manager_widget_launch_parameters";
	public static String APPLICATION_LAUNCH_INTENT_POSTSCRIPT = "purple_robot_manager_widget_launch_postscript";

	public static String HAPTIC_PATTERN_INTENT = "purple_robot_manager_haptic_pattern";
	public static String HAPTIC_PATTERN_NAME = "purple_robot_manager_haptic_pattern_name";
	public static String RINGTONE_INTENT = "purple_robot_manager_ringtone";
	public static String RINGTONE_NAME = "purple_robot_manager_ringtone_name";

	public static String REFRESH_CONFIGURATION = "purple_robot_manager_refresh_configuration";

	public ManagerService()
	{
		super("ManagerService");
	}

	public ManagerService(String name)
	{
		super(name);
	}

	@SuppressWarnings("deprecation")
	protected void onHandleIntent(Intent intent)
	{
		if (HAPTIC_PATTERN_INTENT.equalsIgnoreCase(intent.getAction()))
		{
			String pattern = intent.getStringExtra(HAPTIC_PATTERN_NAME);

			if (!pattern.startsWith("vibrator_"))
				pattern = "vibrator_" + pattern;

			int[] patternSpec = this.getResources().getIntArray(R.array.vibrator_buzz);

			if ("vibrator_blip".equalsIgnoreCase(pattern))
				patternSpec = this.getResources().getIntArray(R.array.vibrator_blip);
			if ("vibrator_sos".equalsIgnoreCase(pattern))
				patternSpec = this.getResources().getIntArray(R.array.vibrator_sos);

			long[] longSpec = new long[patternSpec.length];

			for (int i = 0; i < patternSpec.length; i++)
			{
				longSpec[i] = (long) patternSpec[i];
			}

			Vibrator v = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
			v.cancel();
			v.vibrate(longSpec, -1);
		}
		else if (RINGTONE_INTENT.equalsIgnoreCase(intent.getAction()))
		{
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

			Uri toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

			try
			{
				toneUri = Uri.parse(prefs.getString(SettingsActivity.RINGTONE_KEY, null));

				if (intent.hasExtra(ManagerService.RINGTONE_NAME))
				{
					String name = intent.getStringExtra(ManagerService.RINGTONE_NAME);

					RingtoneManager rm = new RingtoneManager(this);
					rm.setType(RingtoneManager.TYPE_NOTIFICATION);

					Cursor cursor = rm.getCursor();

					do
					{
						String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);

						if (name.equalsIgnoreCase(title))
							toneUri = rm.getRingtoneUri(cursor.getPosition());
					}
					while (cursor.moveToNext());

					cursor.deactivate();
				}
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}

			final Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), toneUri);

			if (r != null)
			{
				Thread t = new Thread(new Runnable()
				{
					public void run()
					{
						r.play();

						try
						{
							while (r.isPlaying())
								Thread.sleep(100);
						}
						catch (InterruptedException e)
						{
							e.printStackTrace();
						}
					}
				});

				t.start();
			}
		}
		else if (APPLICATION_LAUNCH_INTENT.equalsIgnoreCase(intent.getAction()))
		{
			String packageName = intent.getStringExtra(APPLICATION_LAUNCH_INTENT_PACKAGE);

			if (packageName != null)
			{
				Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(packageName);

				if (launchIntent != null)
				{
					String launchParams = intent.getStringExtra(APPLICATION_LAUNCH_INTENT_PARAMETERS);

					if (launchParams != null)
					{
						try
						{
							JSONObject paramsObj = new JSONObject(launchParams);

							@SuppressWarnings("unchecked")
							Iterator<String> keys = paramsObj.keys();

							while (keys.hasNext())
							{
								String key = keys.next();

								launchIntent.putExtra(key, paramsObj.getString(key));
							}
						}
						catch (JSONException e)
						{
							e.printStackTrace();
						}
					}

					this.startActivity(launchIntent);
				}

				String script = intent.getStringExtra(APPLICATION_LAUNCH_INTENT_POSTSCRIPT);

				if (script != null)
				{
					JavaScriptEngine jsEngine = new JavaScriptEngine(this);

					jsEngine.runScript(script);
				}
			}
		}
		else if (REFRESH_CONFIGURATION.equalsIgnoreCase(intent.getAction()))
		{
			Intent funfReloadIntent = new Intent(FunfService.ACTION_RELOAD);

			this.startService(funfReloadIntent);
		}
		else
		{
			JSONConfigFile jsonConfig = JSONConfigFile.getSharedFile(this);

			if (jsonConfig == null)
				return;

			List<Trigger> triggers = jsonConfig.getTriggers(this);

			Date now = new Date();

			for (Trigger trigger : triggers)
			{
				boolean execute = false;

				if (PERIODIC_CHECK_INTENT.equals(intent.getAction()) && trigger instanceof DateTrigger)
				{
					if (trigger.matches(this, now))
						execute = true;
				}
				else if (INCOMING_DATA_INTENT.equals(intent.getAction()))
				{

				}

				if (execute)
				{
					trigger.execute(this);
				}
			}
		}
	}

	public static void setupPeriodicCheck(final Context context)
	{
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

		PendingIntent pi = PendingIntent.getService(context, 0, new Intent(ManagerService.PERIODIC_CHECK_INTENT), PendingIntent.FLAG_UPDATE_CURRENT);

		alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 60000, pi);

		Probe.loadProbeClasses(context);
		OutputPlugin.loadPluginClasses(context);

		Intent funfIntent = new Intent(FunfService.ACTION_RELOAD);
		context.startService(funfIntent);

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		prefs.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener()
		{
	        public void onSharedPreferenceChanged(SharedPreferences prefs, String key)
	        {
	        	Intent reloadIntent = new Intent(ManagerService.REFRESH_CONFIGURATION);

	        	context.startService(reloadIntent);
	        }
	    });
	}
}