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
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Vector;
import java.util.concurrent.Semaphore;


// Started from http://cs.au.dk/~amoeller/WWW/javaweb/server.html

public class WebServer extends Thread
{
    int TOTAL_THREADS = 20;
    WebServerThread threads[] = new WebServerThread[TOTAL_THREADS];

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
                if(c < 0 || c == '\n')
                {
                    break;
                }
                if(c != '\r') {
                    result.append((char) c);
                }
            }

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
                Log.i("x", "WebServerThread.sendFiles 1 path=" + path);
                File f = new File(path);
                if (f.isDirectory())
                {
                    // send the directory listing
                    Log.i("x", "WebServerThread.sendFiles 2 isDirectory CanonicalPath=" + f.getCanonicalPath());

                    File[] mFileList = f.listFiles();
                    Log.i("x", "WebServerThread.sendFiles 3 mFileList=" + mFileList);

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
                        pout.print("<INPUT TYPE=\"file\" NAME=\"UPLOAD\" MULTIPLE=\"true\">\n");
                        pout.print("<INPUT TYPE=\"submit\" VALUE=\"UPLOAD\" NAME=\"submit\">\n");
                        pout.print("</FORM>\r\n");

                        pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
                        pout.print("Create a directory:<BR>\n");
                        pout.print("<INPUT TYPE=\"text\" NAME=\"MKDIR\">\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"MKDIR\" NAME=\"MKDIR\">MAKE DIRECTORY</BUTTON><BR>\n");
                        pout.print("</FORM>\r\n");


                        pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\">\r\n");

                        pout.print("Move the selected files to another directory:<BR>\n");
                        pout.print("<INPUT TYPE=\"text\" NAME=\"MOVEPATH\">\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"MOVE\" NAME=\"MOVE\">MOVE FILES</BUTTON><P>\n");

                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"DELETE\" NAME=\"DELETE\">DELETE SELECTED FILES</BUTTON>\n");
                        pout.print("<BUTTON TYPE=\"submit\" VALUE=\"RENAME\" NAME=\"RENAME\">RENAME SELECTED FILES</BUTTON>\n");
                        pout.print("<TABLE>\r\n");

                        // create the .. entry
                        if (!path.equals("/")) {
                            String truncated = path;
                            int i = truncated.lastIndexOf('/');
                            if (i >= 0 && i < truncated.length() - 1) {
                                truncated = truncated.substring(0, i + 1);
                            }

                            Log.i("x", "WebServerThread.sendFiles 2 truncated=" + truncated);

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
                                    "\" VALUE=\"c\">" +
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
                Log.i("x", "WebServerThread.sendFiles 4 done");
                pout.flush();
            } catch(Exception e)
            {
                Log.v("x", "WebServerThread.sendFiles: " + e);
            }
        }

        public void confirmMove(String path, String movePath, Vector<String> fileList, OutputStream out)
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
                for(int i = 1; i < fileList.size(); i++)
                {
                    pout.print(fileList.get(i) + "<BR>\r\n");
                }

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"CONFIRMMOVE\" NAME=\"CONFIRMMOVE\">MOVE</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"ABORTMOVE\" NAME=\"ABORTMOVE\">DON'T MOVE</BUTTON><P>\n");

                pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" +
                        fullDst +
                        "\" value=\"" +
                        fullDst +
                        "\">\r\n");

                for(int i = 1; i < fileList.size(); i++)
                {
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" +
                            fileList.get(i) +
                            "\" value=\"" +
                            fileList.get(i) +
                            "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Log.v("x", "WebServerThread.confirmMove: " + e);
            }
        }

        public void confirmDelete(String path, Vector<String> fileList, OutputStream out)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");
                pout.print("<B>Really delete the following files in " + path + "?</B><P>\r\n");
                for(int i = 1; i < fileList.size(); i++)
                {
                    pout.print(fileList.get(i) + "<BR>\r\n");
                }

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"CONFIRMDELETE\" NAME=\"CONFIRMDELETE\">DELETE</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"ABORTDELETE\" NAME=\"ABORTDELETE\">DON'T DELETE</BUTTON><P>\n");

                for(int i = 1; i < fileList.size(); i++)
                {
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                        fileList.get(i) + 
                        "\" value=\"" +
                        fileList.get(i) + 
                        "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Log.v("x", "WebServerThread.confirmDelete: " + e);
            }
        }

        public void confirmRename(String path, Vector<String> fileList, OutputStream out)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html");
                pout.print("<B>Rename the following files in " + path + "?</B><P>\r\n");
//                 for(int i = 1; i < fileList.size(); i++)
//                 {
//                     pout.print(fileList.get(i) + "<BR>\r\n");
//                 }
                
                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"CONFIRMRENAME\" NAME=\"CONFIRMRENAME\">RENAME</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"ABORTRENAME\" NAME=\"ABORTRENAME\">DON'T RENAME</BUTTON><P>\n");
                for(int i = 1; i < fileList.size(); i++)
                {
                    pout.print(fileList.get(i) + 
                        " -> " +
                        "<INPUT TYPE=\"text\" id=\"a\" name=\"" +
                        fileList.get(i) + 
                        "\" value=\"" +
                        fileList.get(i) + 
                        "\"><BR>\r\n");
                }
                
                pout.print("</FORM>\r\n");
                
            } catch(Exception e)
            {
                Log.v("x", "WebServerThread.confirmRename: " + e);
            }
        }

        public void renameFiles(String path, OutputStream out, Vector<String> fileList, Vector<String> dstList)
        {
            PrintStream pout = new PrintStream(out);
            boolean failed = false;
            Log.i("x", "WebServerThread.renameFiles 1");
            for(int i = 1; i < fileList.size(); i++)
            {
                Log.i("x", "WebServerThread.renameFiles 2 " +
                        fileList.get(i) +
                        " -> " +
                        dstList.get(i));
                if(!fileList.get(i).equals(dstList.get(i)))
                {
                    File oldFile = new File(path + "/" + fileList.get(i));
                    File newFile = new File(path + "/" + dstList.get(i));
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

            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void moveFiles(String path, Vector<String> fileList, OutputStream out)
        {
            boolean failed = false;
            PrintStream pout = new PrintStream(out);
            String movePath = fileList.get(1);

            for(int i = 2; i < fileList.size(); i++)
            {
                String srcPath = path + "/" + fileList.get(i);
                String dstPath = movePath + "/" + fileList.get(i);
                Log.i("x", "WebServerThread.moveFiles " + srcPath + " -> " + dstPath);
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

        public void deleteFiles(String path, Vector<String> fileList, OutputStream out)
        {
            boolean failed = false;
            PrintStream pout = new PrintStream(out);
            for(int i = 1; i < fileList.size(); i++)
            {
                String fullPath = path + "/" + fileList.get(i);
                Log.i("x", "WebServerThread.deleteFiles " + fullPath);
                File f = new File(fullPath);
                if(!f.delete())
                {
                    // failed
                    failed = true;
                    errorReport(pout, connection, "404", "WebServerThread.deleteFiles: FAILED",
                        "Couldn't delete " + fullPath);
                    break;
                }
            }
            
            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void handlePost(String path, OutputStream out, InputStream in)
        {
            final int GET_BOUNDARY = 1;
            final int GET_START = 2;
            final int GET_FILENAME = 3;
            final int GET_MOVEPATH = 4;
            final int GET_DATA = 5;
            final int GET_TEXT = 6;
            final int GET_EOF = 7;
            int state = GET_BOUNDARY;
            
            final int DO_UNKNOWN = 0;
            final int DO_DELETE = 1;
            final int DO_CONFIRM_DELETE = 2;
            final int DO_ABORT = 3;
            final int DO_RENAME = 4;
            final int DO_CONFIRM_RENAME = 5;
            final int DO_MOVE = 6;
            final int DO_CONFIRM_MOVE = 7;
            final int DO_MKDIR = 8;
            final int DO_UPLOAD = 9;
            int operation = DO_UNKNOWN;

            String filename = "";
            String movePath = "";
            PrintStream pout = new PrintStream(out);
            Vector<String> fileList = new Vector<String>();
            Vector<String> dstList = new Vector<String>();
            int totalFiles = 0;
            boolean mkdirFailed = false;
            boolean mkdirComplete = false;
            boolean done = false;

            try
            {
                String boundary = "";
                while (!done)
                {
                    String misc = readLine(in);
                    Log.i("x", "WebServerThread.handlePost state=" + state + " misc=" + misc);
//                    if (misc.length() == 0)
//                        break;

                    switch (state)
                    {

                        case GET_FILENAME:
                            if(misc.startsWith("Content-Disposition:"))
                            {
// the Content-Disposition changes based on the operation
// Content-Disposition: form-data; name="DELETE"
// Content-Disposition: form-data; name="UPLOAD"; filename="loadlines.png"
                                if(operation == DO_UNKNOWN)
                                {
                                    int nameIndex = misc.indexOf("name=\"");
                                    if(nameIndex >= 0)
                                    {
                                        String[] strings = misc.substring(nameIndex).split("\"");
                                        Log.i("x", "WebServerThread.handlePost operation=" + strings[1]);
                                        if(strings[1].equals("UPLOAD"))
                                        {
                                            operation = DO_UPLOAD;
                                        }
                                        else
                                        if(strings[1].equals("DELETE"))
                                        {
                                            operation = DO_DELETE;
                                        }
                                        else
                                        if(strings[1].equals("CONFIRMDELETE"))
                                        {
                                            operation = DO_CONFIRM_DELETE;
                                        }
                                        else
                                        if(strings[1].equals("MKDIR"))
                                        {
                                            operation = DO_MKDIR;
                                        }
                                        else
// text box containing the move path
                                        if(strings[1].equals("MOVEPATH"))
                                        {
                                            state = GET_MOVEPATH;
                                        }
                                        else
// the move button
                                        if(strings[1].equals("MOVE"))
                                        {
                                            operation = DO_MOVE;
                                        }
                                        else
                                        if(strings[1].equals("RENAME"))
                                        {
                                            operation = DO_RENAME;
                                        }
                                        else
                                        if(strings[1].equals("CONFIRMRENAME"))
                                        {
                                            operation = DO_CONFIRM_RENAME;
                                        }
                                        else
                                        if(strings[1].equals("CONFIRMMOVE"))
                                        {
                                            operation = DO_CONFIRM_MOVE;
                                        }
                                        else
                                        if(strings[1].equals("ABORTRENAME") ||
                                                strings[1].equals("ABORTMOVE") ||
                                                strings[1].equals("ABORTDELETE"))
                                        {
                                            operation = DO_ABORT;
                                        }
                                    }
                                }

                                switch(operation)
                                {
                                    case DO_UPLOAD:
                                    {
                                        String[] strings = misc.split("filename=\"");
                                        if (strings.length > 1)
                                        {
                                            strings = strings[1].split("\"");
                                            //Log.i("x", "WebServerThread.handlePost strings=" + strings.length);

                                            if (strings.length == 0)
                                            {
                                                Log.i("x", "WebServerThread.handlePost no files specified");

                                                state = GET_START;
                                            }
                                            else
                                            {
                                                filename = strings[0];
                                                Log.i("x", "WebServerThread.handlePost filename=" + filename);
                                                state = GET_DATA;
                                            }
                                        }
                                        else
                                        {
                                            // no filename given
                                            Log.i("x", "WebServerThread.handlePost last file received");
                                            state = GET_START;
                                        }
                                        break;
                                    }

                                    case DO_DELETE:
                                    case DO_CONFIRM_DELETE:
                                    case DO_RENAME:
                                    case DO_CONFIRM_RENAME:
                                    case DO_MOVE:
                                    case DO_CONFIRM_MOVE:
                                    {
                                        String[] strings = misc.split("name=\"");
                                        if(strings.length > 1)
                                        {
                                            strings = strings[1].split("\"");
                                            if(strings.length > 0)
                                            {
                                                Log.i("x", "WebServerThread.handlePost GET_FILENAME name=" +
                                                        strings[0]);
                                                fileList.add(strings[0]);
                                            }
                                        }

                                        if(operation == DO_CONFIRM_RENAME)
                                        {
                                            // destination directory is in the current data block
                                            state = GET_TEXT;
                                        }
                                        else
                                        {
                                            state = GET_START;
                                        }
                                        break;
                                    }

                                    case DO_ABORT:
                                        state = GET_START;
                                        break;

                                    case DO_MKDIR:
// only get 1 filename
                                        if(mkdirComplete)
                                        {
                                            state = GET_START;
                                        }
                                        else
                                        {
                                            state = GET_TEXT;
                                        }
                                        break;
                                }
                            }
                            break;

                        case GET_EOF:
                            if(misc.contains(boundary + "--"))
                            {
                                done = true;
                            }
                            break;

                        case GET_START:
// end of the request
                            if(misc.contains(boundary + "--"))
                            {
                                Log.i("x", "WebServerThread.handlePost GET_START 1");
                                switch(operation)
                                {
                                    case DO_UPLOAD:
                                        if(totalFiles == 0)
                                        {
                                            errorReport(pout, connection, "555", "WebServerThread.handlePost: SHIT",
                                                    "No files were selected for uploading.");
                                        }
                                        else 
                                        {
// resend the directory
                                            Log.i("x", "WebServerThread.handlePost GET_START 2");
                                            sendFiles(path, out);
                                        }
                                        break;

                                    case DO_MOVE:
                                        Log.i("x", "WebServerThread.handlePost GET_START -> DO_MOVE file list:");
                                        for(int i = 0; i < fileList.size(); i++)
                                        {
                                            Log.i("x", "WebServerThread.handlePost " + fileList.get(i));
                                        }

                                        confirmMove(path, movePath, fileList, out);
                                        break;

                                    case DO_CONFIRM_MOVE:
                                        moveFiles(path, fileList, out);
                                        break;

                                    case DO_DELETE:
                                        Log.i("x", "WebServerThread.handlePost GET_START -> DO_DELETE file list:");
                                        for(int i = 0; i < fileList.size(); i++)
                                        {
                                            Log.i("x", "WebServerThread.handlePost " + fileList.get(i));
                                        }
                                        
                                        confirmDelete(path, fileList, out);
                                        break;
                                    
                                    case DO_CONFIRM_DELETE:
                                        deleteFiles(path, fileList, out);
                                        break;

                                    case DO_MKDIR:
                                        File file = new File(path + "/" + dstList.get(0));
                                        if(!file.mkdir())
                                        {
                                            errorReport(pout, 
                                                connection, 
                                                "555", 
                                                "WebServerThread.handlePost: MKDIR FAILED",
                                                "Couldn't create the directory " + path + "/" + dstList.get(0));
                                        }
                                        else
                                        {
                                            sendFiles(path, out);
                                        }
                                        break;
                                    
                                    case DO_RENAME:
                                        Log.i("x", "WebServerThread.handlePost GET_START -> DO_RENAME");
                                        confirmRename(path, fileList, out);
                                        break;
                                    
                                    default:
                                    case DO_ABORT:
                                        sendFiles(path, out);
                                        break;
                                }
                                
                                done = true;
                                
                            }
                            else if(misc.contains(boundary))
                            {
                                state = GET_FILENAME;
                            }
                            break;

                        case GET_MOVEPATH:
                            Log.i("x", "WebServerThread.handlePost GET_MOVEPATH misc.length=" + misc.length());
                            if(misc.length() == 0) {
                                byte[] data = readUntil(boundary, in,false);
                                movePath = new String(data, StandardCharsets.UTF_8);
                                Log.i("x", "WebServerThread.handlePost GET_MOVEPATH path=" +
                                    movePath);
                                state = GET_FILENAME;
                            }
                            break;

                        case GET_TEXT:
                            Log.i("x", "WebServerThread.handlePost GET_TEXT misc.length=" + misc.length());
                            if(misc.length() == 0)
                            {
                                byte[] data = readUntil(boundary, in,
                                        operation == DO_CONFIRM_RENAME ||
                                            operation == DO_MOVE);
                                
                                String s = new String(data, StandardCharsets.UTF_8);
                                Log.i("x", "WebServerThread.handlePost GET_TEXT gotEOF=" +
                                        gotEOF +
                                        " path=" +
                                        path +
                                        " filename=" + s);
                                dstList.add(s);

                                mkdirComplete = true;
                                // DO_RENAME gets its filenames in GET_FILENAME
                                if(operation == DO_CONFIRM_RENAME)
                                {
                                    // DO_CONFIRM_RENAME gets its source filenames in GET_FILENAME &
                                    // its destination filenames in GET_TEXT
                                    // exits when the filename block ends in an extra --
                                    if(gotEOF)
                                    {
                                        renameFiles(path, out, fileList, dstList);
                                        done = true;
                                    }
                                    else {
                                        // get the next filename
                                        state = GET_FILENAME;
                                    }
                                }
                                else
                                {
                                    state = GET_START;
                                }
                            }
                            break;

                        case GET_DATA:
                            Log.i("x", "WebServerThread.handlePost GET_DATA misc.length=" + misc.length());
                            // start of the data
                            if(misc.length() == 0)
                            {
                                Log.i("x", "WebServerThread.handlePost GET_DATA 1");
                                byte[] data = readUntil(boundary, in, false);
                                Log.i("x", "WebServerThread.handlePost GET_DATA 2");

                                // write the file
                                String newPath = path + "/" + filename;
                                Log.i("x", "WebServerThread.handlePost writing file to " + newPath);
                                OutputStream fd;
                                boolean failed = false;

                                try {
                                    fd = new FileOutputStream(new File(newPath));
                                    fd.write(data, 0, data.length);
                                    fd.close();
                                    fd = null;
                                } catch(IOException e)
                                {
                                    Log.i("x", "WebServerThread.handlePost: write failed " + e);
                                    failed = true;
                                }

                                if(failed)
                                {
                                    // send the error
                                    errorReport(pout,
                                            connection,
                                            "555",
                                            "WebServerThread.handlePost: SHIT",
                                            "Couldn't create the file " + newPath);
                                    state = GET_EOF;
                                }
                                else {
                                    totalFiles++;

                                    // get the next file
                                    state = GET_FILENAME;
                                }
                            }
                            break;

                        default:
                        case GET_BOUNDARY:
                            // get the boundary code
                            if(misc.startsWith("Content-Type:"))
                            {
                                String[] strings = misc.split("boundary=");
                                if (strings.length > 1)
                                {
                                    boundary = strings[1];
                                    Log.i("x", "WebServerThread.handlePost boundary=" + strings[1]);
                                    state = GET_START;
                                }
                            }
                            break;

                    }
                }

                Log.i("x", "WebServerThread.handlePost done");

            } catch(Exception e)
            {
                Log.i("x", "WebServerThread.handlePost: " + e);
            }
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
                                    //Log.i("x", "WebServerThread.run 2 line=" + line.length());
                                    if(line.length() == 0)
                                    {
                                        break;
                                    }
                                }

                                sendFiles(path, out);
                            }
                            else
// handle a form
                            if (request.startsWith("POST")) 
                            {
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
