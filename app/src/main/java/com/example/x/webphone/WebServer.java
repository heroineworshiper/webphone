/*
 * WEBPHONE
 * Copyright (C) 2020-2024 Adam Williams <broadcast at earthling dot net>
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

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Semaphore;


// Started from http://cs.au.dk/~amoeller/WWW/javaweb/server.html

public class WebServer extends Thread
{
    int TOTAL_THREADS = 20;
    WebServerThread threads[] = new WebServerThread[TOTAL_THREADS];
    final String CHECKED = "__CHECKED";


    public void run()
    {

        ServerSocket socket = null;
        try {
            socket = new ServerSocket(Stuff.PORT);
        } catch (IOException e) {
            Log.v("WebServer", "run: Could not start web server: " + e);
        }

        Log.v("WebServer", "run: started web server on port " + Stuff.PORT);


        // request handler loop
        while (true) {
            Socket connection = null;
            try {
                // wait for request
                connection = socket.accept();
                Log.v("WebServer", "run: got connection");
                if(connection != null) startConnection(connection);

            } catch (IOException e)
            {
                Log.v("WebServer", "run: " + e);
            }
        }
    }


    void startConnection(Socket connection)
    {
        WebServerThread thread = null;
        synchronized(this)
        {
            for(int i = 0; i < TOTAL_THREADS; i++)
            {
                if(threads[i] == null)
                    threads[i] = new WebServerThread();

                if(!threads[i].busy)
                {
                    thread = threads[i];
                    threads[i].startConnection(connection);
                    break;
                }
            }
        }

        if(thread == null)
        {
            Log.v("WebServer", "startConnection: out of threads");
            return;
        }



    }


    private static void log(Socket connection, String msg)
    {
        Log.v("WebServer", "log: " +
//        		new Date() +
//        		" [" +
//        		connection.getInetAddress().getHostAddress() +
//        		":" +
//        		connection.getPort() +
//        		"] " +
                msg);
    }


    private static void errorReport(PrintStream pout, Socket connection,
                                    String code, String title, String msg)
    {
        pout.print("HTTP/1.0 " + code + " " + title + "\r\n" +
                "\r\n" +
                "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\r\n" +
                "<TITLE>" + code + " " + title + "</TITLE>\r\n" +
                "</HEAD><BODY>\r\n" +
                "<H1>" + title + "</H1>\r\n" + msg + "<P>\r\n" +
                "<HR><ADDRESS>Webphone at " +
                connection.getLocalAddress().getHostName() +
                " Port " + connection.getLocalPort() + "</ADDRESS>\r\n" +
                "</BODY></HTML>\r\n");
        log(connection, code + " " + title);
    }

    private static String guessContentType(String path)
    {
        if (path.endsWith(".class"))
            return "application/octet-stream";
        else if (path.endsWith(".html") || path.endsWith(".htm"))
            return "text/html";
        else if (path.endsWith(".gif"))
            return "image/gif";
        else if (path.endsWith(".jpg") || path.endsWith(".jpeg"))
            return "image/jpeg";
        else if (path.endsWith(".js"))
            return "application/javascript";
        else if (path.endsWith(".mp3"))
            return "audio/mpeg3";
        else if (path.endsWith(".mp4"))
            return "video/mp4";
        else if (path.endsWith(".pdf"))
            return "application/pdf";
        else if (path.endsWith(".png"))
            return "image/png";
        else if (path.endsWith(".txt") || path.endsWith(".java"))
            return "text/plain";
        else
            return "text/plain";
    }

    void sendHeader(PrintStream pout, String contentType)
    {
        pout.print("HTTP/1.0 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Date: " + new Date() + "\r\n" +
                "Server: Ultramap\r\n\r\n");
    }


    private static void sendFile(InputStream file, OutputStream out)
    {
        try {
            byte[] buffer = new byte[65536];
            while (file.available() > 0)
                out.write(buffer, 0, file.read(buffer));
        } catch (IOException e)
        {
            Log.v("WebServer", "sendFile " + e);
        }
    }


    class SortByName implements Comparator<DirEntry>
    {
        // Used for sorting in ascending order of
        // roll number
        public int compare(DirEntry a, DirEntry b) {
            int result = a.name.compareTo(b.name);
            if (!Stuff.sortDescending) {
                return result;
            } else
            {
                return -result;
            }
        }
    }

    class SortBySize implements Comparator<DirEntry>
    {
        // Used for sorting in ascending order of
        // roll number
        public int compare(DirEntry a, DirEntry b)
        {
            if(Stuff.sortDescending)
            {
                if(a.size < b.size)
                {
                    return 1;
                }
                else
                {
                    return 0;
                }
            }
            else
            {
                if(b.size < a.size)
                {
                    return 1;
                }
                else
                {
                    return 0;
                }
            }
        }
    }


    class SortByDate implements Comparator<DirEntry>
    {
        // Used for sorting in ascending order of
        // roll number
        public int compare(DirEntry a, DirEntry b)
        {
            if(Stuff.sortDescending)
            {
                if(a.date < b.date)
                {
                    return 1;
                }
                else
                {
                    return 0;
                }
            }
            else
            {
                if(b.date < a.date)
                {
                    return 1;
                }
                else
                {
                    return 0;
                }
            }
        }
    }

    public static boolean isSymlink(String path)
    {
        File file = new File(path);
        try {
            File canon;
            if (file.getParent() == null) {
                canon = file;
            } else {
                File canonDir = file.getParentFile().getCanonicalFile();
                canon = new File(canonDir, file.getName());
            }
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public class WebServerThread extends Thread
    {

        boolean busy = false;
        Socket connection;
        Semaphore lock = new Semaphore(0);

        public WebServerThread()
        {
            start();
        }

        public void startConnection(Socket connection)
        {
            this.connection = connection;
            busy = true;
            lock.release();
        }

        // read the input stream
        // returns -1 at the EOF
        int readChar(InputStream in)
        {
            int result = -1;
            try
            {
                result = in.read();
            }
            catch(IOException e)
            {
            }

            return result;
        }

        // read the input stream until the next newline
        String readLine(InputStream in)
        {
            StringBuilder result = new StringBuilder();

            while(true)
            {
                int c = readChar(in);
                if(c < 0)
                {
                    Log.i("WebServerThread", "readLine c=" + c);
                    break;
                }
                if(c == '\n')
                {
                    result.append((char) c);
                    break;
                }
// strip \r
                if(c != '\r') {
                    result.append((char) c);
                }
            }

//            Log.i("WebServerThread", "readLine text=" + result.toString());
            return result.toString();
        }


        boolean gotEOF = false;
        byte[] readUntil(String text, InputStream in, boolean testEOF)
        {
            byte[] textBytes = text.getBytes();
            byte[] endBuf = new byte[text.length() + 4];
            gotEOF = false;



            // fudge the terminating sequence with extra bytes that tend to occur after the file but may
            // not be standardized
            endBuf[0] = '\r';
            endBuf[1] = '\n';
            endBuf[2] = '-';
            endBuf[3] = '-';
            System.arraycopy(textBytes, 0, endBuf, 4, textBytes.length);

//            for(int i = 0; i < endBuf.length; i++)
//            {
//                Log.i("x", "WebServerThread.readUntil 1 " + endBuf[i]);
//            }
            byte[] result = new byte[1024];
            int offset = 0;

// read until the terminating sequence
            while(true)
            {
                int nextChar = readChar(in);
//                Log.i("x", "WebServerThread.readUntil 1 " + nextChar);
                if(nextChar < 0)
                {
                    break;
                }

// expand the array
                if(offset >= result.length)
                {
                    byte[] result2 = new byte[result.length * 2];
                    System.arraycopy(result, 0, result2, 0, result.length);
                    result = result2;
                }

                result[offset++] = (byte)nextChar;

                if(offset >= endBuf.length) {
                    boolean gotIt = true;
                    int fileEnd = offset - endBuf.length;
                    for (int i = 0; i < endBuf.length; i++) {
                        if (result[fileEnd + i] != endBuf[i]) {
                            gotIt = false;
                            break;
                        }
                    }

// truncate the result & return it
                    if(gotIt)
                    {
                        byte[] result2 = new byte[fileEnd];
                        System.arraycopy(result, 0, result2, 0, fileEnd);
//                        Log.i("x", "WebServerThread.readUntil 2");

// read 2 more characters to test for an EOF.  They'll be \r\n or --
                        if(testEOF) {
                            nextChar = readChar(in);
                            if (nextChar == '-') {
                                nextChar = readChar(in);
                                if (nextChar == '-') {
                                    gotEOF = true;
                                }
                            }
                        }
                        return result2;
                    }
                }
            }

            return null;
        }


// send the big directory listing or a single file
        public void sendFiles(String path, OutputStream out)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                Log.i("WebServerThread", "sendFiles 1 path=" + path);
                File f = new File(path);
                if (f.isDirectory())
                {
                    // send the directory listing
                    Log.i("WebServerThread", "sendFiles 2 isDirectory CanonicalPath=" + f.getCanonicalPath());

                    File[] mFileList = f.listFiles();
                    Log.i("WebServerThread", "sendFiles 3 mFileList=" + mFileList);

                    if (mFileList != null)
                    {
                        DirEntry[] files = new DirEntry[mFileList.length];
                        for (int i = 0; i < mFileList.length; i++) {
                            files[i] = new DirEntry(mFileList[i].getAbsolutePath());
                        }
// TODO: sort by date, name, & size
                        switch (Stuff.sortOrder) {
                            case Stuff.SORT_SIZE:
                                Arrays.sort(files, new SortBySize());
                                break;
                            case Stuff.SORT_DATE:
                                Arrays.sort(files, new SortByDate());
                                break;
                            default:
                            case Stuff.SORT_PATH:
                                Arrays.sort(files, new SortByName());
                                break;
                        }

                        sendHeader(pout, "text/html");
                        pout.print("<B>Index of " + path + "</B><P>\r\n");
                        pout.print("<A HREF=\"" + path + "\"> <B>RELOAD </B></A><BR>\r\n");

// must always encode in multipart data in case the filename has a ?
// the order of the widgets determines the order of the form data
                        pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
                        pout.print("Upload files to this directory:<BR>\n");
                        pout.print("<INPUT TYPE=\"file\" NAME=\"__UPLOAD\" MULTIPLE=\"true\">\n");
                        pout.print("<INPUT TYPE=\"submit\" VALUE=\"UPLOAD\" NAME=\"__UPLOAD\">\n");
                        pout.print("</FORM>\r\n");

                        pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
                        pout.print("Create a directory:<BR>\n");
                        pout.print("<INPUT TYPE=\"text\" NAME=\"__MKDIRPATH\">\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__MKDIR\" NAME=\"__MKDIR\">MAKE DIRECTORY</BUTTON><BR>\n");
                        pout.print("</FORM>\r\n");


                        pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\">\r\n");

                        pout.print("Move the selected files to another directory:<BR>\n");
                        pout.print("<INPUT TYPE=\"text\" NAME=\"__MOVEPATH\">\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__MOVE\" NAME=\"__MOVE\">MOVE FILES</BUTTON><P>\n");

                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__DELETE\" NAME=\"__DELETE\">DELETE</BUTTON>\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__RENAME\" NAME=\"__RENAME\">RENAME</BUTTON>\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__EDIT\" NAME=\"__EDIT\">EDIT</BUTTON>\n");
                        pout.print("<TABLE>\r\n");

                        // create the .. entry
                        if (!path.equals("/")) {
                            String truncated = path;
                            int i = truncated.lastIndexOf('/');
                            if (i >= 0 && i < truncated.length() - 1) {
                                truncated = truncated.substring(0, i + 1);
                            }

                            Log.i("WebServerThread", "sendFiles 2 truncated=" + truncated);

                            String urlText = "<A HREF=\"" +
                                    truncated +
                                    "\">";
                            pout.print("<TR><TD><B></B></TD><TD></TD><TD><B>" +
                                    urlText +
                                    "..</B></TD></TR>\r\n");
                        }

                        for (int i = 0; i < files.length; i++)
                        {
                            String formattedDate = "";
                            //SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
                            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm");
                            formattedDate = sdf.format(files[i].date);

                            String filenameText = files[i].name;
                            String linkText = "<A HREF=\"" +
                                    files[i].path +
                                    "\">";
                            String textBegin = linkText;


                            if (files[i].isDir) {
                                textBegin = "<B>" + linkText;


                                if (isSymlink(files[i].path)) {
                                    File file = new File(files[i].path);
                                    filenameText = files[i].name +
                                            "/ -> " +
                                            file.getCanonicalPath();
                                }
                                else
                                {
                                    filenameText = filenameText + "/";
                                }
                            }

                            pout.print("<TR><TD>" +
                                    textBegin +
                                    files[i].size +
                                "</TD><TD>" +
                                    textBegin +
                                    formattedDate +
                                "</TD><TD>" +
                                    "<INPUT TYPE=\"checkbox\" ID=\"a\" NAME=\"" + 
                                    files[i].name + 
                                    "\" VALUE=\"" + CHECKED + "\">" +
                                    textBegin +
                                    filenameText +
                                "</TD></TR>\r\n"
                            );
                        }

                        pout.print("</TABLE>\r\n");
                        pout.print("</FORM>\r\n");

                    }
                    else
                    {
                        errorReport(pout, connection, "404", "WebServerThread.sendFiles: SHIT",
                                "Couldn't access that directory.");

                    }
                }
                else
                {
                    // send the file
                    try {
                        // send file
                        InputStream file = new FileInputStream(path);
                        sendHeader(pout, guessContentType(path));
                        sendFile(file, out); // send raw file
                        log(connection, "200 OK");
                    } catch (FileNotFoundException e) {
                        // file not found
                        errorReport(pout, connection, "404", "WebServerThread.sendFiles: Not Found",
                                "The requested URL was not found on this server.");
                    }
                }
                Log.i("WebServerThread", "sendFiles 4 done");
                pout.flush();
            } catch(Exception e)
            {
                Log.v("WebServerThread", "sendFiles: " + e);
            }
        }

        public void confirmMove(String path, 
            OutputStream out, 
            Vector<String> fileList,
            String movePath)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");
                String fullDst = "";
                if(movePath.startsWith("/"))
                {
                    fullDst = movePath;
                }
                else
                {
                    fullDst = path + "/" + movePath;
                }

                pout.print("<B>Really move the following files to " + fullDst + "?</B><P>\r\n");
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print(fileList.get(i) + "<BR>\r\n");
                }

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMMOVE\" NAME=\"__CONFIRMMOVE\">MOVE</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"ABORTMOVE\" NAME=\"ABORTMOVE\">DON'T MOVE</BUTTON><P>\n");

// resend the destination
                pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"__MOVEPATH\" value=\"" + fullDst + "\">\r\n");

// resend the file list
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" +
                            fileList.get(i) +
                            "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Log.v("x", "WebServerThread.confirmMove: " + e);
            }
        }

        public void confirmDelete(String path, OutputStream out, Vector<String> fileList)
        {
//Log.i("WebServerThread", "confirmDelete fileList=" + fileList.size());
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");
                pout.print("<B>Really delete the following files in " + path + "?</B><P>\r\n");
                for(int i = 0; i < fileList.size(); i++)
                {
//Log.i("WebServerThread", "confirmDelete file=" + fileList.get(i));
                    pout.print(fileList.get(i) + "<BR>\r\n");
                }

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMDELETE\" NAME=\"__CONFIRMDELETE\">DELETE</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__ABORTDELETE\" NAME=\"__ABORTDELETE\">DON'T DELETE</BUTTON><P>\n");

// resend the file list
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                        fileList.get(i) + 
                        "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Log.i("WebServerThread", "confirmDelete: " + e);
            }
        }

        public void confirmRename(String path, OutputStream out, Vector<String> fileList)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");
                pout.print("<B>Rename the following files in " + path + "?</B><P>\r\n");

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMRENAME\" NAME=\"__CONFIRMRENAME\">RENAME</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__ABORTRENAME\" NAME=\"__ABORTRENAME\">DON'T RENAME</BUTTON><P>\n");
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print(fileList.get(i) + 
                        " -> " +
                        "<INPUT TYPE=\"text\" id=\"a\" name=\"" +
                        fileList.get(i) + 
                        "\" value=\"" +
                        fileList.get(i) + 
                        "\"><BR>\r\n");
                }

// resend the file list
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                        fileList.get(i) + 
                        "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");
                
            } catch(Exception e)
            {
                Log.v("WebServerThread", "confirmRename: " + e);
            }
        }

        public void editFile(String path, 
            OutputStream out, 
            Vector<String> fileList,
            boolean wroteIt)
        {
            Log.i("WebServerThread", "editFile");
            
            if(fileList.size() == 0)
            {
                PrintStream pout = new PrintStream(out);
                errorReport(pout,
                    connection,
                    "555",
                    "WebServerThread.editFile: SHIT",
                    "No file selected for editing");
                return;
            }
            
            try
            {
                String completePath = path + "/" + fileList.get(0);
                InputStream file = new FileInputStream(completePath);
                byte[] buffer = new byte[65536];
                int size = file.available();


                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");

                pout.print(
                    "<style>\n" +
                    "    html, body {\n" +
                    "        height: 100%;\n" +
                    "        margin: 0;\n" +
                    "        padding: 0;\n" +
                    "    }\n" +
                    "    .top {\n" +
                    "        height: auto;\n" +
                    "    }\n" +
                    "    .bottom {\n" +
                    "        height: auto;\n" +
                    "    }\n" +
                    "    .container {\n" +
                    "        display: flex;\n" +
                    "        flex-direction: column;\n" +
                    "        height: 100%;\n" +
                    "    }\n" +
                    "    textarea {\n" +
                    "        flex: 1;\n" +
                    "        resize: none; /* Optional: prevent resizing */\n" +
                    "        width: 100%;\n" +
                    "        height: 100%;\n" +
                    "        box-sizing: border-box;\n" +
                    "        white-space: pre;\n" +
                    "    }\n" +
                    "</style>\n" +
                    
                    
                    "<script>\n" +
                    "    // Function to save the scroll position of the textarea\n" +
                    "    function saveScrollPosition() {\n" +
                    "        var textarea = document.getElementById('a');\n" +
                    "        sessionStorage.setItem('scrollPosition', textarea.scrollTop);\n" +
                    "    }\n" +
                    "\n" +
                    "    // Function to restore the scroll position of the textarea\n" +
                    "    function restoreScrollPosition() {\n" +
                    "        var scrollPosition = sessionStorage.getItem('scrollPosition');\n" +
                    "        if (scrollPosition !== null) {\n" +
                    "            var textarea = document.getElementById('a');\n" +
                    "            textarea.scrollTop = scrollPosition;\n" +
                    "        }\n" +
                    "    }\n" +
                    "\n" +
                    "    // Call the function to restore scroll position after the page has loaded\n" +
                    "    window.onload = restoreScrollPosition;\n" +
                    "</script>\n" +
                    
                    "\n" +
                    "<BODY>\n" +
                    "<FORM CLASS=\"container\" METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\n" +
                    "<DIV CLASS=\"top\">"
                );
                
                
                pout.print("<B>Editing " + completePath + "</B><BR>\r\n");
                pout.print("CR's have been stripped<BR>\n");
                if(wroteIt)
                {
                    pout.print(size + " bytes written<BR>\r\n");
                }
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__EDITSAVE\" NAME=\"__EDITSAVE\">SAVE</BUTTON>\n");
//                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__EDITREVERT\" NAME=\"__EDITREVERT\">REVERT</BUTTON>\n");
                pout.print("</DIV>\n");

                pout.print("<TEXTAREA id=\"a\" name=\"__EDITTEXT\"  onscroll=\"saveScrollPosition()\" style=\"white-space: pre;\">\r\n");


// print the file
                while (file.available() > 0)
                    pout.write(buffer, 0, file.read(buffer));


                pout.print("</TEXTAREA>\r\n");
                
                pout.print("<DIV CLASS=\"bottom\">\n");

                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__EDITQUIT\" NAME=\"__EDITQUIT\">QUIT</BUTTON><BR>\n");

// resend the file name
                pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                    fileList.get(0) + 
                    "\" value=\"" + CHECKED + "\">\r\n");

                pout.print("</DIV>\n</FORM>\r\n");
            } catch(Exception e)
            {
                Log.i("WebServerThread", "editFile: " + e);
            }
        }
        
        public void editSave(String path, 
            OutputStream out, 
            Vector<String> fileList,
            String text)
        {
            Log.i("WebServerThread", "editSave");

            boolean failed = false;
            String newPath = path + "/" + fileList.get(0);
            try {
                OutputStream fd = new FileOutputStream(new File(newPath));
                PrintStream pout = new PrintStream(fd);
                pout.print(text);
                pout.flush();
            } catch(IOException e)
            {
                Log.i("WebServerThread", "doUpload: write FAILED " + e);
                failed = true;
            }

            if(failed)
            {
// send the error
                PrintStream pout = new PrintStream(out);
                errorReport(pout,
                        connection,
                        "555",
                        "WebServerThread.doUpload: SHIT",
                        "Couldn't save the file " + newPath);
            }
            else
            {
                editFile(path, out, fileList, true);
            }
        }

        public void renameFiles(String path, 
            OutputStream out, 
            Vector<String> fileList, 
            Map<String, String> content)
        {
            PrintStream pout = new PrintStream(out);
            boolean failed = false;
            Log.i("WebServerThread", "renameFiles 1");
            for(int i = 0; i < fileList.size(); i++)
            {
                String oldName = fileList.get(i);
                String newName = content.get(oldName);
                if(!oldName.equals(newName))
                {
                    File oldFile = new File(path + "/" + oldName);
                    File newFile = new File(path + "/" + newName);
                    Log.i("WebServerThread", "renameFiles 2 " +
                            oldFile.getPath() +
                            " -> " +
                            newFile.getPath());
                    if(!oldFile.renameTo(newFile))
                    {
                        errorReport(pout, connection,
                                "404",
                                "WebServerThread.renameFiles: FAILED",
                                "Couldn't rename " + oldFile.getPath() + " -> " + newFile.getPath());
                        failed = true;
                        break;
                    }
                }
            }

            Log.i("WebServerThread", "renameFiles 3 failed=" + failed);
            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void doMkdir(String path, 
            OutputStream out, 
            Map<String, String> content)
        {
            PrintStream pout = new PrintStream(out);
            boolean failed = false;
            File file = new File(path + "/" + content.get("__MKDIRPATH"));
            Log.i("WebServerThread", "doMkdir " + file.getPath());

            if(!file.mkdir())
            {
                errorReport(pout, 
                    connection, 
                    "555", 
                    "WebServerThread.doMkdir: MKDIR FAILED",
                    "Couldn't create the directory " + file.getPath());
            }
            else
            {
                sendFiles(path, out);
            }
        }

        public void moveFiles(String path, 
            OutputStream out, 
            Vector<String> fileList,
            String movePath)
        {
            boolean failed = false;
            PrintStream pout = new PrintStream(out);

            for(int i = 0; i < fileList.size(); i++)
            {
                String srcPath = path + "/" + fileList.get(i);
                String dstPath = movePath + "/" + fileList.get(i);
                Log.i("WebServerThread", "moveFiles " + srcPath + " -> " + dstPath);
                if(!srcPath.equals(dstPath))
                {
                    File oldFile = new File(srcPath);
                    File newFile = new File(dstPath);
                    if(!oldFile.renameTo(newFile))
                    {
                        errorReport(pout, connection,
                                "555",
                                "WebServerThread.moveFiles: FAILED",
                                "Couldn't rename " + srcPath + " -> " + dstPath);
                        failed = true;
                        break;
                    }

                }
            }

            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void deleteFiles(String path, OutputStream out, Vector<String> fileList)
        {
            boolean failed = false;
            PrintStream pout = new PrintStream(out);
            for(int i = 0; i < fileList.size(); i++)
            {
                String fullPath = path + "/" + fileList.get(i);
                Log.i("WebServerThread", "deleteFiles " + fullPath);
                File f = new File(fullPath);
                if(!f.delete())
                {
                    // failed
                    failed = true;
                    errorReport(pout, 
                        connection, 
                        "404", 
                        "WebServerThread.deleteFiles: FAILED",
                        "Couldn't delete " + fullPath);
                    break;
                }
            }
            
            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void doUpload(String path, 
            OutputStream out,
            Map<String, byte[]> files)
        {
            PrintStream pout = new PrintStream(out);
// save the files
            boolean failed = false;
            for(String key : files.keySet())
            {
                String filename = key;
                byte[] data = files.get(key);
                String newPath = path + "/" + filename;

                try {
                    OutputStream fd = new FileOutputStream(new File(newPath));
                    fd.write(data, 0, data.length);
                    fd.close();
                    fd = null;
                } catch(IOException e)
                {
                    Log.i("WebServerThread", "doUpload: write FAILED " + e);
                    failed = true;
                }

                if(failed)
                {
                    // send the error
                    errorReport(pout,
                            connection,
                            "555",
                            "WebServerThread.doUpload: SHIT",
                            "Couldn't create the file " + newPath);
                    break;
                }
            }

// send the directory
            if(!failed) sendFiles(path, out);
        }

// debugging.  Dump the post to ADB
        public void dumpPost(String path, InputStream in)
        {
            Log.i("WebServerThread", "dumpPost path=" + path);
            try
            {
                while(true)
                {
                    String text = readLine(in);
                    if (text.length() == 0) break;
                    Log.i("WebServerThread", "dumpPost text=" + text);
                }
                Log.i("WebServerThread", "dumpPost done");
            } catch(Exception e)
            {
                Log.i("WebServerThread", "dumpPost: " + e);
            }
        }


        public String stripLinefeed(String x)
        {
            return x.replace("\n", "").replace("\r", "");
        }

        public String getBoundary(InputStream in)
        {
            while(true)
            {
                String text = readLine(in);
                if(text.length() == 0)
                {
                    Log.i("WebServerThread", "getBoundary no boundary");
                    return "";
                }
                
                String[] strings = text.split("boundary=");
                if(strings.length > 1)
                {
//                    Log.i("WebServerThread", "getBoundary " + strings[1]);
                    return stripLinefeed(strings[1]);
                }
            }
        }

// return true if end of post
        public boolean skipBoundary(String boundary, InputStream in)
        {
            while(true)
            {
                String text = readLine(in);
                if(text.length() == 0)
                {
                    return true;
                }
                if(text.contains("--" + boundary + "--"))
                {
                    return true;
                }
                if(text.contains(boundary))
                {
                    return false;
                }
            }
        }


        public byte[] getData(String boundary, InputStream in)
        {
// boundary for data has extra starting bytes
            byte[] boundary2 = new byte[boundary.length() + 4];
            boundary2[0] = '\r';
            boundary2[1] = '\n';
            boundary2[2] = '-';
            boundary2[3] = '-';
            System.arraycopy(boundary.getBytes(), 0, boundary2, 4, boundary.getBytes().length);

// skip Content-Type & empty line
            String text = readLine(in);
            if(text.contains("Content-Type:"))
                readLine(in);
            byte[] result = new byte[1024];
            int offset = 0;
// read until the terminating sequence
            while(true)
            {
                int nextChar = readChar(in);
// end of file
                if(nextChar < 0)
                    break;

// expand the array
                if(offset >= result.length)
                {
                    byte[] result2 = new byte[result.length * 2];
                    System.arraycopy(result, 0, result2, 0, result.length);
                    result = result2;
                }
                
                result[offset++] = (byte)nextChar;

// test for boundary
                if(offset >= boundary2.length) {
                    boolean gotIt = true;
                    int fileEnd = offset - boundary2.length;
                    for (int i = 0; i < boundary2.length; i++) {
                        if (result[fileEnd + i] != boundary2[i]) {
                            gotIt = false;
                            break;
                        }
                    }

// truncate the result & return it
                    if(gotIt)
                    {
                        byte[] result2 = new byte[fileEnd];
                        System.arraycopy(result, 0, result2, 0, fileEnd);
                        return result2;
                    }
                }
            }
            return result;
        }

// returns the name & a filename if it exists
        public Map<String, String> getContentDisposition(InputStream in)
        {
            Map<String, String> result = new HashMap<>();
            result.put("filename", "");
            result.put("name", "");

            while(true)
            {
                String text = readLine(in);
                if(text.length() == 0)
                {
                    Log.i("WebServerThread", "getContentDisposition EOF");
                    return result;
                }
                if(text.startsWith("Content-Disposition:"))
                {
                    int filenameIndex = text.indexOf("filename=\"");
                    if(filenameIndex >= 0)
                    {
                        String[] strings = text.substring(filenameIndex).split("\"");
//Log.i("WebServerThread", "getContentDisposition filename=" + strings[1]);
                        if(strings.length > 2)
                            result.put("filename", strings[1]);
                    }

                    int nameIndex = text.indexOf("name=\"");
                    if(nameIndex >= 0)
                    {
                        String[] strings = text.substring(nameIndex).split("\"");
                        if(strings.length > 2)
                            result.put("name", strings[1]);
                    }
                    return result;
                }
            }
        }

// returns the text value
        public Map<String, String> getContentValue(String boundary, InputStream in)
        {
            Map<String, String> result = new HashMap<>();
            String value = "";

Log.i("WebServerThread", "getContentValue 1");
// skip the 1st line
            String line = readLine(in);
//Log.i("WebServerThread", "getContentValue 2 text=" + text);

// the value
            while(true)
            {
                line = readLine(in);
//Log.i("WebServerThread", "getContentValue 3 line=" + line);
                if(!line.contains(boundary))
                    value += line;
                else
                {
// delete the last newline
                    if(value.length() > 0 &&
                        value.charAt(value.length() - 1) == '\n')
                        value = value.substring(0, value.length() - 1);
                    result.put("value", value);
                    if(line.contains("--" + boundary + "--"))
                        result.put("eof", "true");
                    break;
                }
            }
//Log.i("WebServerThread", "getContentValue 4 result=" + result);
            return result;
        }

// convert the post into tables
        public void handlePost(String path, OutputStream out, InputStream in)
        {
            String boundary = getBoundary(in);
// read up to next boundary
            skipBoundary(boundary, in);
// make a table of all the content, uploaded files, & selected filenames
            Map<String, String> content = new HashMap<>();
            Vector<String> fileList = new Vector<String>();
            Map<String, byte[]> files = new HashMap<>();
            while(true)
            {
                Map<String, String> result = getContentDisposition(in);
                String filename = result.get("filename");
                String name = result.get("name");
//                Log.i("WebServerThread", "handlePost name=" + name + " fileName=" + filename);

                if(!name.equals(""))
                {
                    if(!filename.equals(""))
                    {
                        byte[] data = getData(boundary, in);
                        files.put(filename, data);
                    }
                    else
                    {
                        result = getContentValue(boundary, in);
//Log.i("WebServerThread", "handlePost name=" + name + " value=" + value);

                        String value = result.get("value");
                        boolean isEOF = result.get("eof") != null;
                        if(value.equals(CHECKED))
                            fileList.add(name);
                        else
                            content.put(name, value);

                        if(isEOF) break;
//                        if(skipBoundary(boundary, in))
//                            break;
                    }
                }
                else
                    break;
            }

            Log.i("WebServerThread", "handlePost done");
// print the key values
            for (String key : content.keySet()) 
                Log.i("WebServerThread", "handlePost key=" + key + " value=\"" + content.get(key) + "\"");
// print all the filenames
            for(int i = 0; i < fileList.size(); i++)
                Log.i("WebServerThread", "handlePost selected file=" + fileList.get(i));
// print all the data
            for(String key : files.keySet())
                Log.i("WebServerThread", "handlePost filename=" + key + 
                    " length=" + files.get(key).length +
                    " " + new String(files.get(key), StandardCharsets.UTF_8));


// perform the operation
//            Log.i("WebServerThread", "handlePost UPLOAD=" + content.get("UPLOAD"));
            if(content.get("__MKDIR") != null)
                doMkdir(path, out, content);
            else
            if(content.get("__UPLOAD") != null)
                doUpload(path, out, files);
            else
            if(content.get("__DELETE") != null)
                confirmDelete(path, out, fileList);
            else
            if(content.get("__MOVE") != null)
                confirmMove(path, out, fileList, content.get("__MOVEPATH"));
            else
            if(content.get("__CONFIRMMOVE") != null)
                moveFiles(path, out, fileList, content.get("__MOVEPATH"));
            else
            if(content.get("__CONFIRMDELETE") != null)
                deleteFiles(path, out, fileList);
            else
            if(content.get("__RENAME") != null)
                confirmRename(path, out, fileList);
            else
            if(content.get("__CONFIRMRENAME") != null)
                renameFiles(path, out, fileList, content);
            else
            if(content.get("__EDIT") != null)
                editFile(path, out, fileList, false);
            else
            if(content.get("__EDITSAVE") != null)
                editSave(path, out, fileList, content.get("__EDITTEXT"));
            else
//             if(content.get("__EDITREVERT") != null)
//                 editFile(path, out, fileList);
//             else
            if(content.get("__ABORTDELETE") != null ||
                content.get("__ABORTRENAME") != null ||
                content.get("__ABORTMOVE") != null ||
                content.get("__EDITQUIT") != null)
                sendFiles(path, out);
        }


        public void run()
        {
            while(true)
            {
                try {
                    lock.acquire();


					Log.v("WebServerThread", "run: running 1");
                    InputStream in = connection.getInputStream();
                    OutputStream out = new BufferedOutputStream(connection.getOutputStream());



// read first line of request (ignore the rest)
                    String request = readLine(in);
                    Log.i("x", "WebServerThread.run request=" + request);

                    if(request.length() > 13) {
                        String req = request.substring(4, request.length() - 9).trim();
                        Log.i("x", "WebServerThread.run request=" + request + " req=" + req);

// extract the path name
//                        if (req.startsWith("/get?")) {
                            String path = req;
//                            int end = path.indexOf('?');
//                            if (end >= 0) path = path.substring(end + 1);

                            // strip ending /
                            while (path.length() > 1 &&
                                    path.lastIndexOf('/') == path.length() - 1) {
                                path = path.substring(0, path.length() - 1);
                            }

                            Log.i("x", "WebServerThread.run 1 path=" + path);
// get the file
                            if (request.startsWith("GET")) 
                            {
                                // flush the socket to avoid a disconnection by peer
                                while(true)
                                {
                                    //Log.i("x", "WebServerThread.run 1");

                                    String line = readLine(in);
                                    Log.i("WebServerThread", "run 2 line=" + line);
                                    if(line.length() == 0 ||
                                        line.charAt(0) == '\n')
                                    {
                                        break;
                                    }
                                }
                                Log.i("WebServerThread", "run 3 done");

                                sendFiles(path, out);
                            }
                            else
// handle a form
                            if (request.startsWith("POST")) 
                            {
//                                dumpPost(path, in);
                                handlePost(path, out, in);
                            }
//                        }
                    }

                    Log.i("x", "WebServerThread.run: finished");
                    out.flush();
                    if (connection != null)
                    {
                        connection.close();
                    }
                } catch(Exception e)
                {
                    Log.i("x", "WebServerThread.run: " + e);
                }


                busy = false;
            }
        }
    }

}
