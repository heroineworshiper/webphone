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

import java.io.File;
import java.util.Arrays;
import java.util.Vector;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.io.FileInputStream;


public class Stuff
{
    static int PORT = 8088;
    static boolean sortDescending = false;
    static final int SORT_PATH = 0;
    static final int SORT_SIZE = 1;
    static final int SORT_DATE = 2;
    static int sortOrder = SORT_PATH;
    static String private_dir = "";


    public static void main(String[] args)
    {
        Stuff.initialize();
        WebServer webServer = new WebServer();
        webServer.run();
    }

    static void initialize()
    {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(settingsFile())) 
        {
            properties.load(input);
            String order = properties.getProperty("sortOrder");
            String descending = properties.getProperty("descending");
            
            if(!order.isEmpty())
            {
                sortOrder = Integer.parseInt(order);
            }
            if(!descending.isEmpty())
            {
                int value = Integer.parseInt(descending);
                if(value == 0)
                    sortDescending = false;
                else
                    sortDescending = true;
            }
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            e.printStackTrace();
        }
    }


    static void log(String x, String y)
    {
        System.out.println(x + ":" + y);
    }

    static String settingsFile()
    {
        String userHome = System.getProperty("user.home");
        String filename = userHome + "/.webphonerc";
        return filename;
    }

    static void saveDefaults()
    {
		Properties properties = new Properties();
        
		
		properties.setProperty("sortOrder", String.valueOf(sortOrder));
		properties.setProperty("descending", String.valueOf(sortDescending ? 1 : 0));

        try (FileOutputStream output = new FileOutputStream(settingsFile())) 
        {
            // Write the properties to the file with an optional comment
            properties.store(output, "");
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
            e.printStackTrace();
        }    
    }

};




