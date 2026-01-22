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


package com.example.x.webphone;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import java.util.UUID;


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
            Stuff.log("WebServer", "run: Could not start web server: " + e);
        }

        Stuff.log("WebServer", "run: started web server on port " + Stuff.PORT);


        // request handler loop
        while (true) {
            Socket connection = null;
            try {
                // wait for request
                connection = socket.accept();
                Stuff.log("WebServer", "run: got connection from " +
                        connection.getInetAddress().toString() +
                        " IPv4=" + (connection.getInetAddress() instanceof Inet4Address));
                if(connection != null &&
                    (connection.getInetAddress() instanceof Inet4Address)) startConnection(connection);

            } catch (IOException e)
            {
                Stuff.log("WebServer", "run: " + e);
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
            Stuff.log("WebServer", "startConnection: out of threads");
            try
            {
                connection.close();
            } catch(Exception e)
            {
            }
            return;
        }



    }


    private static void log(Socket connection, String msg)
    {
        Stuff.log("WebServer", "log: " +
//        		new Date() +
//        		" [" +
//        		connection.getInetAddress().getHostAddress() +
//        		":" +
//        		connection.getPort() +
//        		"] " +
                msg);
    }

    private static String getTheURL(Socket connection)
    {
        return connection.getLocalAddress().getHostName() + ":" + connection.getLocalPort();
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
                "<HR><ADDRESS>Webphone at " + getTheURL(connection) + "</ADDRESS>\r\n" +
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

    void sendHeader(PrintStream pout, String contentType, long size)
    {
        pout.print("HTTP/1.0 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n");
        if(size > 0)
            pout.print("Content-Length: " + size + "\r\n");
        pout.print("Date: " + new Date() + "\r\n" +
                "Server: WebPhone\r\n\r\n");
    }


    private static void sendFile(InputStream file, OutputStream out)
    {
        try {
            byte[] buffer = new byte[65536];
            while (file.available() > 0)
                out.write(buffer, 0, file.read(buffer));
        } catch (IOException e)
        {
            Stuff.log("WebServer", "sendFile " + e);
        }
    }


    class SortByName implements Comparator<DirEntry>
    {
        // Used for sorting in ascending order of
        // roll number
        public int compare(DirEntry a, DirEntry b) {
            int result = a.name.toLowerCase().compareTo(b.name.toLowerCase());
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
                    return -1;
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
                    return -1;
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
                    return -1;
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
                    return -1;
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
        int readChar(BufferedInputStream in)
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
        String readLine(BufferedInputStream in)
        {
            StringBuilder result = new StringBuilder();
//            int total_read = 0;

            while(true)
            {
                int c = readChar(in);
//                total_read += 1;

                if(c < 0)
                {
                    Stuff.log("WebServerThread", "readLine c=" + c);
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

//            Stuff.log("WebServerThread", "readLine total_read=" + total_read);
            return result.toString();
        }


        boolean gotEOF = false;
        byte[] readUntil(String text, BufferedInputStream in, boolean testEOF)
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
//                Stuff.log("x", "WebServerThread.readUntil 1 " + endBuf[i]);
//            }
            byte[] result = new byte[1024];
            int offset = 0;

// read until the terminating sequence
            while(true)
            {
                int nextChar = readChar(in);
//                Stuff.log("x", "WebServerThread.readUntil 1 " + nextChar);
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
//                        Stuff.log("x", "WebServerThread.readUntil 2");

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


        public String sortButton(int field, String path)
        {
            final String[] sortText = 
            {
                "path", "PATH", 
                "size", "SIZE", 
                "date", "DATE" 
            };
            String result = "<TD><B>";
            String directionText;
            if(Stuff.sortOrder == field)
            {
                if(Stuff.sortDescending) 
                    directionText = "_0";
                else
                    directionText = "_1";
            }
            else
            {
                if(Stuff.sortDescending) 
                    directionText = "_1";
                else
                    directionText = "_0";
            }
            result += "<A HREF=\"" + path + 
                "?sort_" + field + 
                directionText + "\">";
            if(Stuff.sortOrder == field)
            {
                result += sortText[field * 2 + 1];
                if(Stuff.sortDescending)
                    result += "&#8593;";
                else
                    result += "&#8595;";
            }
            else
            {
                result += sortText[field * 2];
            }

            result += "</A></TD>\r\n";
            return result;
        }

// convert File argument to HTML escape codes
// HTML & URLEncoder classes didn't work.
        public String encodeHtml(String in, boolean is_href)
        {
            StringBuilder htmlEncodedName = new StringBuilder();
            for(char c : in.toCharArray())
            {
                int code = (int)c;
                if(code >= 128 || c == '<' || c == '>') 
                {
                    if(is_href)
                    {
// create % codes for A HREF
                        try
                        {
                            htmlEncodedName.append(
                                URLEncoder.encode(
                                    Character.toString(c), 
                                    "UTF_8"));
                        } catch(Exception e) 
                        {
                        }
                    }
                    else
                        htmlEncodedName.append("&#" + code + ";");
                }
                else
                    htmlEncodedName.append(c);
            }
            return htmlEncodedName.toString();
        }

// convert HTML &#...; codes to a File argument
// android.text.Html.fromHtml is not portable
        public String decodeHtml(String in)
        {
            StringBuilder decodedText = new StringBuilder();
            final int GET_CODE1 = 0;
            final int GET_CODE2 = 1;
            final int GET_NUMBER = 2;
            int state = GET_CODE1;
            StringBuilder number = new StringBuilder();
            for(char c : in.toCharArray())
            {
                int code = (int)c;
                switch(state)
                {
                    case GET_CODE1:
                        if(c == '&') 
                            state = GET_CODE2;
                        else
                            decodedText.append(c);
                        break;
                    case GET_CODE2:
                        if(c == '#') 
                        {
                            state = GET_NUMBER;
                            number.setLength(0);
                        }
                        else
                        {
                            state = GET_CODE1;
                            decodedText.append(c);
                        }
                        break;
                    case GET_NUMBER:
                        if(c == ';') 
                        {
                            state = GET_CODE1;
                            decodedText.append((char)Integer.parseInt(number.toString()));
                        }
                        else
                            number.append(c);
                        break;
                }   
            }
            return decodedText.toString();
        }




// send the big directory listing or a single file
        public void sendFiles(String path, OutputStream out)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8.toString());
                Stuff.log("WebServerThread", "sendFiles 1 path=" + path + 
                    " decodedPath=" + decodedPath);
                File f = new File(decodedPath);
                if (f.isDirectory())
                {
                    // send the directory listing
                    Stuff.log("WebServerThread", "sendFiles 2 isDirectory CanonicalPath=" + f.getCanonicalPath());

                    File[] mFileList = f.listFiles();
                    Stuff.log("WebServerThread", "sendFiles 3 mFileList=" + mFileList);

                    if (mFileList != null)
                    {
                        DirEntry[] files = new DirEntry[mFileList.length];
                        for (int i = 0; i < mFileList.length; i++) {
                            files[i] = new DirEntry(mFileList[i].getAbsolutePath());
                        }
                        switch (Stuff.sortOrder) {
                            case Stuff.SORT_SIZE:
                                //Stuff.log("WebServerThread", "sendFiles SORT_SIZE");
                                Arrays.sort(files, new SortBySize());
                                break;
                            case Stuff.SORT_DATE:
                                //Stuff.log("WebServerThread", "sendFiles SORT_DATE");
                                Arrays.sort(files, new SortByDate());
                                break;
                            default:
                            case Stuff.SORT_PATH:
                                //Stuff.log("WebServerThread", "sendFiles SORT_PATH");
                                Arrays.sort(files, new SortByName());
                                break;
                        }

                        sendHeader(pout, "text/html", -1);


// don't underline links
                        pout.print("<style>\n" +
                            "a {\n" +
                            "    text-decoration: none;\n" +
                            "}\n" +
                            "</style>\n\n");

                        pout.print("<B>Index of " + decodedPath + "</B><BR>\r\n");
                        pout.print("<A HREF=\"" + path + "\"> <B>RELOAD </B></A><BR>\r\n");


// create the .. entry
                        if (!path.equals("/")) {
                            String truncated = path;
                            int i = truncated.lastIndexOf('/');
                            if (i >= 0 && i < truncated.length() - 1) {
                                truncated = truncated.substring(0, i + 1);
                            }

                            Stuff.log("WebServerThread", "sendFiles 2 truncated=" + truncated);

                            String urlText = "<A HREF=\"" +
                                    truncated +
                                    "\"><B>PARENT DIR</B></A>";
                            pout.print(urlText + "\r\n");
//                            pout.print("<TR><TD></TD><TD></TD><TD>" +
//                                    urlText +
//                                    "</TD></TR>\r\n");
                        }


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
                        pout.print("<BUTTON TYPE=\"button\" onclick=\"selectAll()\">CHECK ALL</button>\n");
                        pout.print("<TABLE>\r\n");

// create the sort options
                        pout.print("<TR>\r\n");
                        pout.print(sortButton(Stuff.SORT_SIZE, path));
                        pout.print(sortButton(Stuff.SORT_DATE, path));
                        pout.print(sortButton(Stuff.SORT_PATH, path));

                        pout.print("</TR>\r\n");
                        pout.print("<TR><TD style=\"height: 1px;\" bgcolor=\"000000\" COLSPAN=3></TD></TR>\r\n");

                        for (int i = 0; i < files.length; i++)
                        {
                            String formattedDate = "";
                            //SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
                            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm");
                            formattedDate = sdf.format(files[i].date);

                            String printedName = encodeHtml(files[i].name, false);
                            String linkPath = encodeHtml(files[i].path, true);
                            String checkboxName = printedName;

Stuff.log("WebServerThread", "sendFiles HREF path=" + files[i].path +
    " printedName=" + printedName.toString());

                            String linkText = "<A HREF=\"" +
                                    linkPath +
                                    "\">";
                            String textBegin = linkText;


                            if (files[i].isDir)
                            {
                                textBegin = "<B>" + linkText;
                                printedName += "/";
                            }

                            if (isSymlink(files[i].path)) 
                            {
                                File file = new File(files[i].path);
                                textBegin = "<I>" + textBegin;
                                printedName = printedName +
                                        " -> " +
                                        file.getCanonicalPath();
                            }

                            pout.print("<TR><TD>" +
                                    textBegin +
                                    files[i].size +
                                "</TD><TD>" +
                                    textBegin +
                                    formattedDate +
                                "</TD><TD>" +
                                    "<INPUT TYPE=\"checkbox\" CLASS=\"item\" ID=\"a\" NAME=\"" + 
                                    checkboxName + 
                                    "\" VALUE=\"" + CHECKED + "\">" +
                                    textBegin +
                                    printedName +
                                "</TD></TR>\r\n"
                            );
                        }

                        pout.print("</TABLE>\r\n");
                        pout.print("</FORM>\r\n");
                        pout.print(
                            "<script>\n" +
                            "    // Function to check/uncheck all checkboxes with class \"item\"\n" +
                            "    function selectAll() {\n" +
                            "        const checkboxes = document.querySelectorAll('.item');\n" +
                            "        var checked = false;\n" +
                            "        if(checkboxes.length > 0)\n" +
                            "            checked = checkboxes[0].checked;\n" +
                            "        checkboxes.forEach(checkbox => {\n" +
                            "            checkbox.checked = !checked;\n" +
                            "        });\n" +
                            "    }\n" +
                            "\n" +
                            "</script>\n"
                        );

                    }
                    else
                    {
                        errorReport(pout, connection, "404", "WebServerThread.sendFiles: SHIT",
                                "Couldn't access that directory.  " +
                                "How about going to this award winning page:<P>" +
                                "<A HREF=\"http://" + getTheURL(connection) + "/sdcard" + "\">" + getTheURL(connection) + "/sdcard" + "</A>");

                    }
                }
                else
                {
// send the file
                    try {
                        // send file
                        InputStream file = new FileInputStream(decodedPath);
                        sendHeader(pout, guessContentType(decodedPath), f.length());
                        sendFile(file, out); // send raw file
                        log(connection, "200 OK");
                    } catch (FileNotFoundException e) {
                        // file not found
                        errorReport(pout, connection, "404", "WebServerThread.sendFiles: Not Found",
                                "The requested URL was not found on this server.");
                    }
                }
                Stuff.log("WebServerThread", "sendFiles 4 done");
                pout.flush();
            } catch(Exception e)
            {
                Stuff.log("WebServerThread", "sendFiles: " + e);
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
                sendHeader(pout, "text/html", -1);
                String fullDst = "";
                if(movePath.startsWith("/"))
                {
                    fullDst = encodeHtml(movePath, false);
                }
                else
                {
                    fullDst = encodeHtml(path + "/" + movePath, false);
                }

                pout.print("<B>Really move the following files to " + fullDst + "?</B><P>\r\n");
                for(int i = 0; i < fileList.size(); i++)
                {
                    pout.print(encodeHtml(fileList.get(i), false) + "<BR>\r\n");
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
                            encodeHtml(fileList.get(i), false) +
                            "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Stuff.log("x", "WebServerThread.confirmMove: " + e);
            }
        }

        public void confirmDelete(String path, OutputStream out, Vector<String> fileList)
        {
//Stuff.log("WebServerThread", "confirmDelete fileList=" + fileList.size());
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html", -1);
                pout.print("<B>Really delete the following files in " + path + "?</B><P>\r\n");
                for(int i = 0; i < fileList.size(); i++)
                {
//Stuff.log("WebServerThread", "confirmDelete file=" + fileList.get(i));
                    pout.print(encodeHtml(fileList.get(i), false) + "<BR>\r\n");
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
                        encodeHtml(fileList.get(i), false) + 
                        "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");

            } catch(Exception e)
            {
                Stuff.log("WebServerThread", "confirmDelete: " + e);
            }
        }

        public void confirmRename(String path, OutputStream out, Vector<String> fileList)
        {
            try
            {
                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html", -1);
                pout.print("<style>\r\n" +
                    ".full-width {\r\n" +
                    "  width: 100%;\r\n" +
                    "}\r\n" +
                    "</style>\r\n");
                pout.print("<B>Rename the following files in " + path + "?</B><P>\r\n");

                pout.print("<P>\r\n");
                pout.print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMRENAME\" NAME=\"__CONFIRMRENAME\">RENAME</BUTTON>\n");
                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__ABORTRENAME\" NAME=\"__ABORTRENAME\">DON'T RENAME</BUTTON><P>\n");
                for(int i = 0; i < fileList.size(); i++)
                {
                    String htmlEncoded = encodeHtml(fileList.get(i), false);
                    pout.print(htmlEncoded + 
                        " -> " +
                        "<INPUT TYPE=\"text\" class=\"full-width\" id=\"a\" name=\"" +
                        htmlEncoded + 
                        "\" value=\"" +
                        htmlEncoded + 
                        "\"><P>\r\n");
                }

// resend the file list
                for(int i = 0; i < fileList.size(); i++)
                {
                    String htmlEncoded = encodeHtml(fileList.get(i), false);
                    pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                        htmlEncoded + 
                        "\" value=\"" + CHECKED + "\">\r\n");
                }

                pout.print("</FORM>\r\n");
                
            } catch(Exception e)
            {
                Stuff.log("WebServerThread", "confirmRename: " + e);
            }
        }

        public void editFile(String path, 
            OutputStream out, 
            Vector<String> fileList,
            boolean wroteIt)
        {
            Stuff.log("WebServerThread", "editFile");
            
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
                int size = file.available();
                if(size > 0x100000)
                {
                    errorReport(new PrintStream(out),
                        connection,
                        "404",
                        "WebServerThread.editFile: SHIT",
                        "File too large.");
                    return;
                }

                PrintStream pout = new PrintStream(out);
                sendHeader(pout, "text/html", -1);

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
                
                
                pout.print("<B>Editing " + encodeHtml(completePath, false) + "</B><BR>\r\n");
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
                byte[] buffer = new byte[size];
                int bytesRead = file.read(buffer);
// must convert to HTML to avoid breaking text area
                String str1 = new String(buffer, StandardCharsets.UTF_8);
                String str2 = encodeHtml(str1, false);
                pout.print(str2);  

//                while (file.available() > 0)
//                    pout.write(buffer, 0, file.read(buffer));


                pout.print("</TEXTAREA>\r\n");
                
                pout.print("<DIV CLASS=\"bottom\">\n");

                pout.print("<BUTTON TYPE=\"submit\" VALUE=\"__EDITQUIT\" NAME=\"__EDITQUIT\">QUIT</BUTTON><BR>\n");

// resend the file name
                pout.print("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"" + 
                    encodeHtml(fileList.get(0), false) + 
                    "\" value=\"" + CHECKED + "\">\r\n");

                pout.print("</DIV>\n</FORM>\r\n");
            } catch(Exception e)
            {
                Stuff.log("WebServerThread", "editFile: " + e);
            }
        }
        
        public void editSave(String path, 
            OutputStream out, 
            Vector<String> fileList,
            String text)
        {
            Stuff.log("WebServerThread", "editSave");

            boolean failed = false;
            String newPath = path + "/" + fileList.get(0);
            try {
                OutputStream fd = new FileOutputStream(new File(newPath));
                PrintStream pout = new PrintStream(fd);
                pout.print(text);
                pout.flush();
            } catch(IOException e)
            {
                Stuff.log("WebServerThread", "editSave: write FAILED " + e);
                failed = true;
            }

            if(failed)
            {
// send the error
                PrintStream pout = new PrintStream(out);
                errorReport(pout,
                        connection,
                        "404",
                        "WebServerThread.editSave: SHIT",
                        "Couldn't save the file " + encodeHtml(newPath, false));
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
            Stuff.log("WebServerThread", "renameFiles 1");
            for(int i = 0; i < fileList.size(); i++)
            {
                String oldName = fileList.get(i);
                String newName = content.get(oldName);
                if(!oldName.equals(newName))
                {
                    File oldFile = new File(path + "/" + oldName);
                    File newFile = new File(path + "/" + newName);
                    Stuff.log("WebServerThread", "renameFiles 2 " +
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

            Stuff.log("WebServerThread", "renameFiles 3 failed=" + failed);
            if(!failed)
            {
                sendFiles(path, out);
            }
        }

        public void doMkdir(String path, 
            OutputStream out, 
            String name)
        {
            PrintStream pout = new PrintStream(out);
            File file = new File(path + "/" + name);
            Stuff.log("WebServerThread", "doMkdir " + file.getPath());

            if(!file.mkdir())
            {
                errorReport(pout, 
                    connection, 
                    "404", 
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
                Stuff.log("WebServerThread", "moveFiles " + srcPath + " -> " + dstPath);
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
                Stuff.log("WebServerThread", "deleteFiles " + fullPath);
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
            Map<String, String> files)
        {
// rename the temp files
            boolean failed = false;
            for(String key : files.keySet())
            {
                String filename = key;
                String tempPath = files.get(key);
                String newPath = path + "/" + filename;
                File oldFile = new File(tempPath);
                File newFile = new File(newPath);
Stuff.log("WebServerThread", "doUpload: renaming " + tempPath + " to " + newPath);
                oldFile.renameTo(newFile);
            }

// send the directory
            if(!failed) sendFiles(path, out);
        }

// debugging.  Dump the post to a file
        public void dumpPost(String path, BufferedInputStream in)
        {
            Stuff.log("WebServerThread", "dumpPost path=" + path);
            try
            {
                BufferedOutputStream output = new BufferedOutputStream(
                    new FileOutputStream(
                        new File("/sdcard/debug")));
                byte[] buffer = new byte[65536];
                while(true)
                {
                    int read_result = in.read(buffer, 0, buffer.length);
                    if(read_result <= 0) break;
                    output.write(buffer, 0, read_result);
                }
                output.close();
                Stuff.log("WebServerThread", "dumpPost done");
            } catch(Exception e)
            {
                Stuff.log("WebServerThread", "dumpPost: " + e);
            }
        }


        public String stripLinefeed(String x)
        {
            return x.replace("\n", "").replace("\r", "");
        }

        public String getBoundary(BufferedInputStream in)
        {
            while(true)
            {
                String text = readLine(in);
                if(text.length() == 0)
                {
                    Stuff.log("WebServerThread", "getBoundary no boundary");
                    return "";
                }
                
                String[] strings = text.split("boundary=");
                if(strings.length > 1)
                {
//                    Stuff.log("WebServerThread", "getBoundary " + strings[1]);
                    return stripLinefeed(strings[1]);
                }
            }
        }

// return true if end of post
        public boolean skipBoundary(String boundary, BufferedInputStream in)
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


        public boolean getData(String boundary, BufferedInputStream in, BufferedOutputStream out)
        {
// boundary for data has 4 extra starting bytes
            byte[] boundary2 = new byte[boundary.length() + 4];
            boundary2[0] = '\r';
            boundary2[1] = '\n';
            boundary2[2] = '-';
            boundary2[3] = '-';
            System.arraycopy(boundary.getBytes(), 0, boundary2, 4, boundary.getBytes().length);

            byte[] fifo = new byte[65536];
            int fifo_size = 0;

// skip Content-Type & empty line
            String text = readLine(in);
            if(text.contains("Content-Type:"))
                readLine(in);

// read until the boundary
            boolean error = false;
            int read_result = 0;
            while(!error)
            {
// mark the current position
                int maxRead = fifo.length - fifo_size;
                in.mark(fifo.length);
// start of the read in the fifo
                int read_start = fifo_size;
                try
                {
                    read_result = in.read(fifo, fifo_size, maxRead);
                } catch(IOException e)
                {
                    Stuff.log("WebServerThread", "getData: read failed");
                    error = true;
                }

// failed or EOF
                if(error || read_result <= 0)
                {
                    Stuff.log("WebServerThread", "getData: EOF");
                    return true;
                }
                fifo_size += read_result;

// test fifo for boundary
                int score = 0;
                boolean gotBoundary = false;
// end of boundary in the fifo buffer
                int boundaryEnd = 0;
                for(int i = 0; i < fifo_size; i++)
                {
                    if(fifo[i] == boundary2[score])
                    {
                        score++;
                        if(score >= boundary2.length)
                        {
                            gotBoundary = true;
                            boundaryEnd = i + 1;
                            break;
                        }
                    }
                    else
                    {
                        score = 0;
                        if(fifo[i] == boundary2[0]) score = 1;
                    }
                }

                if(gotBoundary)
                {
// rewind the input to the end of the boundary, as if we read 1 character at a time
                    try {
                        in.reset();
// skip length of the last read up to the end of the boundary
                        in.skip(boundaryEnd - read_start);

// DEBUG: print future data                        
// in.mark(1024);
// StringBuilder test = new StringBuilder();
// for(int i = 0; i < 16; i++) test.append(" " + readChar(in));
// in.reset();
// Stuff.log("WebServerThread", "getData test=" + test.toString());

                    } catch(IOException e) 
                    {
                        Stuff.log("WebServerThread", "getData: in can't rewind 1");
                        error = true;
                    }
                    fifo_size = boundaryEnd;
                }
                else
                {
                    try {
                        in.reset();
                        in.skip(read_result);
                    } catch(IOException e) 
                    {
                        Stuff.log("WebServerThread", "getData: in can't rewind 2");
                        error = true;
                    }
                }

//                 Stuff.log("WebServerThread", "getData: gotBoundary=" + gotBoundary +
//                     " boundary2.length=" + boundary2.length +
//                     " fifo_size=" + fifo_size);

// at most, maxWrite can be written
// if we didn't get the boundary, eat the 1st byte of the boundary size to advance
// the scan window
                int maxWrite = fifo_size - boundary2.length;
                if(!gotBoundary) maxWrite += 1;
                if(maxWrite > 0)
                {
                    try
                    {
                        out.write(fifo, 0, maxWrite);
                    } catch(IOException e)
                    {
                        Stuff.log("WebServerThread", "getData: write failed");
                        return true;
                    }

                    if(gotBoundary)
                    {
                        fifo_size = 0;
                        break;
                    }
                    else
                    {
// drain the fifo
                        System.arraycopy(fifo, maxWrite, fifo, 0, fifo_size - maxWrite);
                        fifo_size -= maxWrite;
                    }
                }
            }

            return error;
        }

// returns the name & a filename if it exists
        public Map<String, String> getContentDisposition(BufferedInputStream in,
            String boundary)
        {
            Map<String, String> result = new HashMap<>();
            result.put("filename", "");
            result.put("name", "");

            while(true)
            {
                String text = readLine(in);
//Stuff.log("WebServerThread", "getContentDisposition text=" + text + 
//" boundary=" + boundary);
                if(text.length() == 0 ||
                    text.contains("--" + boundary + "--"))
                {
                    Stuff.log("WebServerThread", "getContentDisposition EOF");
                    return result;
                }
                if(text.startsWith("Content-Disposition:"))
                {
Stuff.log("WebServerThread", "getContentDisposition text=" + text);
                    int filenameIndex = text.indexOf("filename=\"");
                    if(filenameIndex >= 0)
                    {
                        String[] strings = text.substring(filenameIndex).split("\"");
//Stuff.log("WebServerThread", "getContentDisposition filename=" + strings[1]);
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
        public Map<String, String> getContentValue(String boundary, BufferedInputStream in)
        {
            Map<String, String> result = new HashMap<>();
            String value = "";

//Stuff.log("WebServerThread", "getContentValue 1");
// skip the 1st line
            String line = readLine(in);
//Stuff.log("WebServerThread", "getContentValue 2 text=" + text);

// the value
            while(true)
            {
                line = readLine(in);
//Stuff.log("WebServerThread", "getContentValue 3 line=" + line);
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
//Stuff.log("WebServerThread", "getContentValue 4 result=" + result);
            return result;
        }

// convert the post into tables
        public void handlePost(String path, OutputStream out, BufferedInputStream in)
        {
            String boundary = getBoundary(in);
// read up to next boundary
            skipBoundary(boundary, in);
// make a table of all the content, uploaded files, & selected filenames
            Map<String, String> content = new HashMap<>();
            Vector<String> fileList = new Vector<String>();
// map files to temp files
            Map<String, String> files = new HashMap<>();

            while(true)
            {
//Stuff.log("WebServerThread", "handlePost 1");
                Map<String, String> result = getContentDisposition(in, boundary);
                String filename = result.get("filename");
                String name = result.get("name");
//                String decodedFilename = Html.fromHtml(filename).toString();
// decode the uploaded filename
                String decodedFilename = decodeHtml(filename);


Stuff.log("WebServerThread", "handlePost filename=" + filename + " decodedFilename=" + decodedFilename);

//for(int i = 0; i < decodedFilename.length(); i++)
//Stuff.log("WebServerThread", "i=" + i + " c=" + (int)decodedFilename.charAt(i));

for (String key : result.keySet()) 
Stuff.log("WebServerThread", "handlePost key=" + key + " value=\"" + result.get(key) + "\"");


                if(!name.equals(""))
                {
                    if(!filename.equals(""))
                    {
// uploaded files have to be written to a temporary or we'll run out of memory
                        String tempPath = path + "/.temp" + UUID.randomUUID();
                        BufferedOutputStream output = null;
                        boolean error = false;
Stuff.log("WebServerThread", "handlePost saving temp to " + tempPath);

                        try {
                            output = new BufferedOutputStream(new FileOutputStream(new File(tempPath)));
                        } catch(IOException e)
                        {
                            Stuff.log("WebServerThread", "handlePost: write FAILED " + e);
                            error = true;
                        }


                        if(!error) 
                        {
                            if(getData(boundary, in, output))
                                error = true;


                            try {
                                output.close();
                            } catch(IOException e)
                            {
                            }
                        }

                        if(error)
                        {
                            PrintStream pout = new PrintStream(out);
                            // send the error
                            errorReport(pout,
                                    connection,
                                    "555",
                                    "WebServerThread.handlePost: SHIT",
                                    "Couldn't create the temp file " + tempPath);
                            break;
                        }
                        else
                        {
                            files.put(decodedFilename, tempPath);
                            content.put(name, decodedFilename);
                        }
                    }
                    else
                    {
                        result = getContentValue(boundary, in);
// decode the NAME= tag
                        String value = result.get("value");
                        boolean isEOF = result.get("eof") != null;
                        String decodedName = decodeHtml(name);
                        
                        
Stuff.log("WebServerThread", "handlePost name=" + name + " value=" + value);
                        if(value.equals(CHECKED))
                            fileList.add(decodedName);
                        else
                        {
                            String decodedValue = decodeHtml(value);
                            content.put(decodedName, decodedValue);
                        }

                        if(isEOF) break;
                    }
                }
                else
                    break;
            }

            Stuff.log("WebServerThread", "handlePost done");
// print the key values
            for (String key : content.keySet()) 
                Stuff.log("WebServerThread", "handlePost key=" + key + " value=\"" + content.get(key) + "\"");
// print all the filenames
            for(int i = 0; i < fileList.size(); i++)
                Stuff.log("WebServerThread", "handlePost selected file=" + fileList.get(i));
// print all the data
            for(String key : files.keySet())
                Stuff.log("WebServerThread", "handlePost filename=" + key + 
                    " temp file=" + files.get(key));


// perform the operation
//            Stuff.log("WebServerThread", "handlePost UPLOAD=" + content.get("UPLOAD"));
            if(content.get("__MKDIR") != null)
                doMkdir(path, out, content.get("__MKDIRPATH"));
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


					Stuff.log("WebServerThread", "run: running 1");
                    BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                    OutputStream out = new BufferedOutputStream(connection.getOutputStream());



// read first line of request (ignore the rest)
                    String request = readLine(in);
                    Stuff.log("x", "WebServerThread.run request=" + request);

                    String[] parts = request.split("\\s+");
                    if(parts.length >= 2)
                    {
                        String req = parts[0];
                        String path = parts[1];
// extract the path name
//                        String path = request.substring(4, request.length() - 9).trim();
                        Stuff.log("x", 
                            "WebServerThread.run request=" + request + 
                            " req=" + req + 
                            " path=" + path);

// handle a sort command.  Android doesn't allow ? in regular filenames
                            int sort_index = path.indexOf('?');
                            if(sort_index >= 0)
                            {
                                String sort_command = path.substring(sort_index + 1);
                                path = path.substring(0, sort_index);
                                Stuff.log("x", "WebServerThread.run 1 sort_command=" + sort_command);
                                if(sort_command.substring(0, 4).equals("sort"))
                                {
                                    Stuff.sortOrder = Integer.valueOf(sort_command.substring(5, 6));
                                    int descending = Integer.valueOf(sort_command.substring(7, 8));
                                    if(descending == 0)
                                        Stuff.sortDescending = false;
                                    else
                                        Stuff.sortDescending = true;
                                    Stuff.saveDefaults();
                                }
                            }

// strip ending /
                            while (path.length() > 1 &&
                                    path.lastIndexOf('/') == path.length() - 1) {
                                path = path.substring(0, path.length() - 1);
                            }

// get the file
                            if (req.startsWith("GET")) 
                            {
                                // flush the socket to avoid a disconnection by peer
                                while(true)
                                {
                                    //Stuff.log("x", "WebServerThread.run 1");

                                    String line = readLine(in);
                                    Stuff.log("WebServerThread", "run 2 line=" + line);
                                    if(line.length() == 0 ||
                                        line.charAt(0) == '\n')
                                    {
                                        break;
                                    }
                                }
                                Stuff.log("WebServerThread", "run 3 done");

                                sendFiles(path, out);
                            }
                            else
// handle a form
                            if (req.startsWith("POST")) 
                            {
//                                dumpPost(path, in);
                                handlePost(path, out, in);
                            }
//                        }
                    }

                    Stuff.log("x", "WebServerThread.run: finished");
                    out.flush();
                    if (connection != null)
                    {
                        connection.close();
                    }
                } catch(Exception e)
                {
                    Stuff.log("x", "WebServerThread.run: " + e);
                }


                busy = false;
            }
        }
    }

}
