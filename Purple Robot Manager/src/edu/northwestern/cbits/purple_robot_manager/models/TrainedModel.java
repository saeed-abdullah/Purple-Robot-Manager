package edu.northwestern.cbits.purple_robot_manager.models;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import edu.northwestern.cbits.purple_robot_manager.EncryptionManager;
import edu.northwestern.cbits.purple_robot_manager.R;
import edu.northwestern.cbits.purple_robot_manager.logging.LogManager;
import edu.northwestern.cbits.purple_robot_manager.probes.Probe;

/**
 * Provides the infrastructure for fetching model definitions by URL and 
 * generating dynamic model instances capable of evaluating incoming 
 * information.
 */

public abstract class TrainedModel extends Model 
{
	protected Uri _source = null;
	protected String _sourceHash = null;
	protected boolean _inited = false;
	protected String _name = null;

	protected double _accuracy = 0.0;
	private long _lastCheck = 0;

	/**
	 * Returns the URL of the model as the identifying URI.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#uri()
	 */
	
	public Uri uri() 
	{
		return this._source;
	}

	public TrainedModel(final Context context, Uri uri) 
	{
		this._source = uri;
		this._sourceHash = EncryptionManager.getInstance().createHash(context, uri.toString());
		
		final TrainedModel me = this;
		
		Runnable r = new Runnable()
		{
			public void run() 
			{
				String hash = EncryptionManager.getInstance().createHash(context, me._source.toString());
				
				SharedPreferences prefs = Probe.getPreferences(context);

				File internalStorage = context.getFilesDir();

				if (prefs.getBoolean("config_external_storage", false))
					internalStorage = context.getExternalFilesDir(null);

				if (internalStorage != null && !internalStorage.exists())
					internalStorage.mkdirs();

				File modelsFolder = new File(internalStorage, "persisted_models");

				if (modelsFolder != null && !modelsFolder.exists())
					modelsFolder.mkdirs();
				
				String contents = null;
				File cachedModel = new File(modelsFolder, hash);
				
				try 
				{
					contents = FileUtils.readFileToString(cachedModel);
				}
				catch (IOException e) 
				{

				}
				
				try 
				{
					URL u = new URL(me._source.toString());

			        BufferedReader in = new BufferedReader(new InputStreamReader(u.openStream()));
			        
			        StringBuffer sb = new StringBuffer();
			        
			        String inputLine = null;
			        
			        while ((inputLine = in.readLine()) != null)
			        	sb.append(inputLine);

			        in.close();
			        
			        contents = sb.toString();
				} 
				catch (MalformedURLException e) 
				{
					LogManager.getInstance(context).logException(e);
				} 
				catch (IOException e) 
				{
					LogManager.getInstance(context).logException(e);
				} 
				
				if (contents != null)
				{
					try
					{
				        JSONObject json = new JSONObject(contents);
				        
				        me._name = json.getString("class");
				        me._accuracy = json.getDouble("accuracy");
				        
				        me.generateModel(context, json.get("model"));

				        FileUtils.writeStringToFile(cachedModel, contents);

				        me._inited = true;
					}
					catch (JSONException e) 
					{
						LogManager.getInstance(context).logException(e);
					} 
					catch (IOException e) 
					{
						LogManager.getInstance(context).logException(e);
					}
				}
			}
		};
		
		Thread t = new Thread(r);
		t.start();
	}
	
	
	/**
	 * Provides the name of the model, as specified by the "class" key in the 
	 * JSON definition.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#title(android.content.Context)
	 */

	public String title(Context context) 
	{
		return this._name;
	}
	
	
	/**
	 * Provides a placeholder summary. TODO: Add feature to specify description 
	 * in JSON definition.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#summary(android.content.Context)
	 */
	
	public String summary(Context context)
	{
		return context.getString(R.string.summary_model_unknown);
	}

	
	/**
	 * Returns an MD5 hash of the model's URL to be used a unique identifier by 
	 * the rest of the system.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#getPreferenceKey()
	 */

	public String getPreferenceKey() 
	{
		return this._sourceHash;
	}
	
	
	/**
	 *  Returns the URL of the model definition to be used as the internal 
	 * identifier within.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#name(android.content.Context)
	 */

	public String name(Context context) 
	{
		return this._source.toString();
	}
	
	
	/**
	 * Calls TrainedModel.evaluateModel method within a Thread on implementing 
	 * subclasses to generate a prediction for the provided snapshot. When a
	 * prediction becomes available, transmits the prediction through the rest
	 * of the data processing pipeline.
	 * 
	 * @see edu.northwestern.cbits.purple_robot_manager.models.Model#predict(android.content.Context, java.util.Map)
	 */
	
	public void predict(final Context context, final Map<String, Object> snapshot) 
	{
		if (this._inited == false || this.enabled(context) == false)
			return;
		
		long now = System.currentTimeMillis();
		
		if (now - this._lastCheck < 1000)
		{
			this._lastCheck = now;
			
			return;
		}

		final TrainedModel me = this;
		
		Runnable r = new Runnable()
		{
			public void run() 
			{
				Object value = me.evaluateModel(context, snapshot);

				Log.e("PR", "GOT PREDICTION: " + value);
				
				if (value == null)
				{
					
				}
				else if (value instanceof Double)
				{
					Double doubleValue = (Double) value;

					me.transmitPrediction(context, doubleValue.doubleValue(), me._accuracy);
				}
				else
					me.transmitPrediction(context, value.toString(), me._accuracy);
			}
		};
		
		Thread t = new Thread(r);
		t.start();
	}

	/**
	 * Generates the dynamic structures needed to evaluate the model provided. 
	 * Subclasses will implement this method to provide custom parsing logic
	 * depending upon the representation provided.
	 * 
	 * @param context
	 * 
	 * @param model Java object obtained from JSONObject.get that encodes the 
	 *              desired model.
	 */
	

	protected abstract void generateModel(Context context, Object model); 


	/**
	 * Evaluates the states provided to generate a prediction describing the 
	 * world at that point in time. This method will evaluate the input provided 
	 * against the data structure constructed in the generateModel method.
	 *  
	 * @param context
	 * @param snapshot Key-value pairs describing the states to be evaluated.
	 * 
	 * @return Prediction generated by the model.
	 */
	
	protected abstract Object evaluateModel(Context context, Map<String, Object> snapshot);
}
