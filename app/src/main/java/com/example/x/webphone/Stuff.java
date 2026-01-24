/*
 * WEBPHONE
 * Copyright (C) 2020-2026 Adam Williams <broadcast at earthling dot net>
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 */

// android compatability layer


package com.example.x.webphone;


import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Vector;

public class Stuff extends Service
{
// the directory tree we'll be playing
    static String TOP = "/";  // the internal flash
    static DirEntry dir;
    static DirEntry currentDir;
    static Activity activity;

// The singleton
    static Stuff stuff;
    static boolean enableServer;
// update interval for alarm in ms
	static int DT = 1000;
	// The port number
    static int PORT = 8088;
    static String WWWHOME = "";
    static final int SORT_PATH = 0;
    static final int SORT_SIZE = 1;
    static final int SORT_DATE = 2;
    static int sortOrder = SORT_PATH;
    static boolean sortDescending = false;
    static String private_dir = "";

    static void initialize(Activity activity)
    {
        stuff = new Stuff();
		Intent serviceIntent = new Intent(activity, Stuff.class);
		activity.startService(serviceIntent);
        stuff.activity = activity;

		SharedPreferences file = null;
		file = activity.getSharedPreferences("webphone", 0);
		sortOrder = file.getInt("sortOrder", SORT_PATH);
        int descending = file.getInt("descending", 0);
        if(descending == 0)
            Stuff.sortDescending = false;
        else
            Stuff.sortDescending = true;
        private_dir = activity.getFilesDir().toString();
    }

    static void log(String x, String y)
    {
        Log.i(x, y);
    }

    static void saveDefaults()
    {
		SharedPreferences file2 = null;
		SharedPreferences.Editor file = null;
		file2 = activity.getSharedPreferences("webphone", 0);
		file = file2.edit();
		
		file.putInt("sortOrder", sortOrder);
		file.putInt("descending", sortDescending ? 1 : 0);
		file.commit();
    }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) 
    {
        
    
    
        if(enableServer)
        {
            AlarmManager alarmManager = (AlarmManager) stuff.activity.getSystemService(
					Activity.ALARM_SERVICE);
		    Intent i = new Intent(this, Stuff.class);
		    PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		    alarmManager.cancel(pi);
		    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				    SystemClock.elapsedRealtime() + DT,
				    pi);
            
            
            
        }

        return 0;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // convert the directory to just the names
    static Vector<String> dirToStrings(DirEntry dir)
    {
        Vector<String> result = new Vector<String>();

        if(dir.parent != null) {
            result.add("..");
        }

        for(int i = 0; i < dir.contents.size(); i++)
        {
            result.add(dir.contents.get(i).getNamePlusFiles());
        }

        return result;
    }

// load the directory tree recursively
    static void loadDir()
    {
        dir = new DirEntry(TOP);
        dir.isDir = true;
        loadDir(dir);
        currentDir = dir;
    }

    static void loadDir(DirEntry parent)
    {
        // doesn't follow symbolic links
        File[] files = new File(parent.path).listFiles();

        //Log.i("x", "Stuff.loadDir path=" + parent.path + " files=" + files);
        if(files != null) {
            Arrays.sort(files);
            for (int i = 0; i < files.length; i++) {
                DirEntry entry = new DirEntry(files[i].getPath());
                parent.contents.add(entry);
                entry.isDir = files[i].isDirectory();
                entry.parent = parent;
                if (entry.isDir) {
                    loadDir(entry);
                }
            }
        }
        
    }
    
    static void dumpDir(int indent, DirEntry dir)
    {
        String text = new String();
        for(int i = 0; i < indent; i++)
        {
            text += ' ';
        }
        
        Log.i("x", "dumpDir " + text + dir.path + " " + dir.contents.size() + " files");
        if(dir.isDir && indent < 1)
        {
            for(int i = 0; i < dir.contents.size(); i++)
            {
                dumpDir(indent + 1, dir.contents.get(i));
            }
        }
    }
    
}




