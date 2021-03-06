/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Slog;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.DisplayManager;
import com.android.systemui.statusbar.policy.DisplayHotPlugPolicy;
import android.media.MediaPlayer;
import android.media.AudioSystem;
import com.android.systemui.R;
import android.os.SystemProperties;
import android.provider.Settings;
/*import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import android.util.Log;*/

public class DisplayController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.DisplayController";

    private Context mContext;
    private final  DisplayManager mDisplayManager;
	private DisplayHotPlugPolicy  mDispHotPolicy = null;
	private static final boolean SHOW_HDMIPLUG_IN_CALL = true;
    private static final boolean SHOW_TVPLUG_IN_CALL = true;

    public DisplayController(Context context) {
        mContext = context;

		mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
		mDispHotPolicy = new StatusBarPadHotPlug();
		
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_HDMISTATUS_CHANGED);
		filter.addAction(Intent.ACTION_TVDACSTATUS_CHANGED);
        context.registerReceiver(this, filter);
    }
    
    private void onHdmiPlugChanged(Intent intent)
	{
		mDispHotPolicy.onHdmiPlugChanged(intent);
	}
	
	private void onTvDacPlugChanged(Intent intent)
	{
		mDispHotPolicy.onTvDacPlugChanged(intent);
	}

    public void onReceive(Context context, Intent intent) 
    {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_HDMISTATUS_CHANGED)) 
        {
            onHdmiPlugChanged(intent);
        }
        else if(action.equals(Intent.ACTION_TVDACSTATUS_CHANGED))
        {
			onTvDacPlugChanged(intent);
        }
    }
    
    private class StatusBarPadHotPlug implements DisplayHotPlugPolicy
    {
    	StatusBarPadHotPlug()
    	{
    		
    	}
    	
    	private void onHdmiPlugIn(Intent intent) 
		{
			int     maxscreen;
			//int     maxhdmimode;
			int	hdmimode;
			
	        if (SHOW_HDMIPLUG_IN_CALL) 
			{
	          	Slog.d(TAG,"onHdmiPlugIn Starting!\n");
	          	mDisplayManager.setDisplayParameter(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
			//maxhdmimode	= mDisplayManager.getMaxHdmiMode();
	          	//mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_HDMI,maxhdmimode);
	          	String  str = Settings.System.getString(mContext.getContentResolver(), Settings.System.HDMI_RESOLUTION);
	          	hdmimode = mDisplayManager.getMaxHdmiMode();
	          	if (str!=null && str.equals("1080_60") ) {
	          		hdmimode = DisplayManager.DISPLAY_TVFORMAT_1080P_60HZ;
	          	}
	          	if (str!=null && str.equals("1080_50") ) {
	          		hdmimode = DisplayManager.DISPLAY_TVFORMAT_1080P_50HZ;
	          	}
	          	if (str!=null && str.equals("720_60") ) {
	          		hdmimode = DisplayManager.DISPLAY_TVFORMAT_720P_60HZ;
	          	}
	          	if (str!=null && str.equals("720_50") ) {
	          		hdmimode = DisplayManager.DISPLAY_TVFORMAT_720P_50HZ;
	          	}
	          	//if (str==null || str.equals("auto") ) {
	          	//	hdmimode = mDisplayManager.getMaxHdmiMode();
	          	//}
	          	mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_HDMI,hdmimode);
		        mDisplayManager.setDisplayMode(DisplayManager.DISPLAY_MODE_DUALSAME);
				maxscreen = mDisplayManager.getMaxWidthDisplay();
				MediaPlayer.setScreen(1);
				SystemProperties.set("audio.routing", Integer.toString(AudioSystem.DEVICE_OUT_AUX_DIGITAL));
				AudioSystem.setParameters("routing="+AudioSystem.DEVICE_OUT_AUX_DIGITAL);
			/*File file = new File("/cache/.hdmi");
			try{
				file.createNewFile();
			}catch(IOException e)
			{
				Log.e("IOException", "Exception in create new File");
			}*/
				//Camera.setCameraScreen(1);
		        //mDisplayManager.setDisplayOutputType(0,DisplayManager.DISPLAY_OUTPUT_TYPE_HDMI,DisplayManager.DISPLAY_TVFORMAT_1080P_60HZ);
	        }
	    }
	
		private void onTvDacYPbPrPlugIn(Intent intent)
		{
			mDisplayManager.setDisplayParameter(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
	       mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_TV,DisplayManager.DISPLAY_TVFORMAT_720P_60HZ);
	       mDisplayManager.setDisplayMode(DisplayManager.DISPLAY_MODE_DUALSAME);
		   //MediaPlayer.setScreen(1);
		   //Camera.setCameraScreen(1);
		}
		
		private void onTvDacCVBSPlugIn(Intent intent)
		{
		   mDisplayManager.setDisplayParameter(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
	       mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_TV,DisplayManager.DISPLAY_TVFORMAT_NTSC);
	       mDisplayManager.setDisplayMode(DisplayManager.DISPLAY_MODE_DUALSAME);
		   //MediaPlayer.setScreen(1);
		   //Camera.setCameraScreen(1);
		}
	
		private void onHdmiPlugOut(Intent intent)
		{
			int     maxscreen;
			
			Slog.d(TAG,"onHdmiPlugOut Starting!\n");
			mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_NONE,0);
	      	mDisplayManager.setDisplayParameter(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
	        mDisplayManager.setDisplayMode(DisplayManager.DISPLAY_MODE_SINGLE);
	        maxscreen = mDisplayManager.getMaxWidthDisplay();
	        MediaPlayer.setScreen(0);
			SystemProperties.set("audio.routing", Integer.toString(AudioSystem.DEVICE_OUT_SPEAKER));
			AudioSystem.setParameters("routing="+AudioSystem.DEVICE_OUT_SPEAKER);
			//Camera.setCameraScreen(0);
	        //mDisplayManager.setDisplayOutputType(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
		/*File file = new File("/cache/.hdmi");
		file.delete();*/
		}
	
		private void onTvDacPlugOut(Intent intent)
		{
			Slog.d(TAG,"onTvDacPlugOut Starting!\n");
			mDisplayManager.setDisplayParameter(1,DisplayManager.DISPLAY_OUTPUT_TYPE_NONE,0);
			mDisplayManager.setDisplayParameter(0,DisplayManager.DISPLAY_OUTPUT_TYPE_LCD,0);
	        mDisplayManager.setDisplayMode(DisplayManager.DISPLAY_MODE_SINGLE);
	        //MediaPlayer.setScreen(0);
			//Camera.setCameraScreen(0);
		}
		
		public void onHdmiPlugChanged(Intent intent)
		{
			int   hdmiplug;
			
			hdmiplug = intent.getIntExtra(DisplayManager.EXTRA_HDMISTATUS, 0);
			if(hdmiplug == 1)
			{
				onHdmiPlugIn(intent);
			}
			else
			{
				onHdmiPlugOut(intent);
			}
		}
		
		public void onTvDacPlugChanged(Intent intent)
		{
			int   tvdacplug;
			
			tvdacplug = intent.getIntExtra(DisplayManager.EXTRA_TVSTATUS, 0);
			if(tvdacplug == 1)
			{
				onTvDacYPbPrPlugIn(intent);
			}
			else if(tvdacplug == 2)
			{
				onTvDacCVBSPlugIn(intent);
			}
			else
			{
				onTvDacPlugOut(intent);
			}
		}
    }
    
    private class StatusBarTVDHotPlug implements DisplayHotPlugPolicy
    {
    	StatusBarTVDHotPlug()
    	{
    		
    	}

		public void onHdmiPlugChanged(Intent intent)
		{
		}
		
		public void onTvDacPlugChanged(Intent intent)
		{
		}
    }
}
