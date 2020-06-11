/*
 * WEBPHONE
 * Copyright (C) 2020 Adam Williams <broadcast at earthling dot net>
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


package com.example.x.webphone;

import java.io.File;
import java.util.Vector;

class DirEntry
{

    String path;
    String name;
    boolean isDir;
    long size;
    long date;


    Vector<DirEntry> contents = new Vector<DirEntry>();
    DirEntry parent;


    DirEntry(String path)
    {
        this.path = path;
        int i = path.lastIndexOf('/');
        if(i >= 0) {
            name = path.substring(i + 1);
        }
        else
        {
            name = path;
        }
        File file =  new File(path);
        isDir = file.isDirectory();
        size = file.length();
        date = file.lastModified();
    }


    String getPathPlusFiles()
    {
        String string = new String(path);
        if(isDir)
        {
            string += " (" + String.valueOf(contents.size()) + ")";
        }
        return string;

    }

    String getNamePlusFiles()
    {
        String string = new String(name);
        if(isDir)
        {
            string += " (" + String.valueOf(contents.size()) + ")";
        }
        return string;

    }
}
