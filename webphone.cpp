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

// C++ port of the whole thing, because of the large amount of space
// required by a JVM.
// g++ -g -std=c++11 -D_LARGEFILE_SOURCE -D_LARGEFILE64_SOURCE -D_FILE_OFFSET_BITS=64 -O2 -o webphone webphone.cpp -lpthread

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <semaphore.h>
#include <pthread.h>
#include <unistd.h>
#include <ctype.h>
#include <dirent.h>
#include <time.h>

#include <string>
#include <vector>
#include <map>

#define TEXTLEN 1024
#define CHECKED "__CHECKED"




class DirEntry
{
public:
    DirEntry(std::string *path2, const char *filename)
    {
        isDir = 0;
        size = 0;
        date = 0;
        isLink = 0;
// create full path
        path.assign(*path2);
// strip last /
        while(path.length() > 0 &&
            path[path.length() - 1] == '/')
            path.erase(path.length() - 1);
        path.push_back('/');
        path.append(filename);
        name.assign(filename);
        struct stat ostat;
        if(!stat(path.c_str(), &ostat))
        {
            isDir = S_ISDIR(ostat.st_mode);
            size = ostat.st_size;
            date = ostat.st_mtime;

            struct stat ostat2;
            lstat(path.c_str(), &ostat2);
            isLink = S_ISLNK(ostat2.st_mode);
        }
    }

    std::string path;
    std::string name;
    int isDir;
    int isLink;
    int64_t size;
    time_t date;
};

class ContentDisposition
{
public:
    std::string filename;
    std::string name;
};

class ContentValue
{
public:
    ContentValue()
    {
        eof = 0;
    }

    std::string value;
    int eof;
};

class Stuff
{
public:
    static const int PORT = 8088;
    static const int PORT2 = 8098;
#define SORT_PATH 0
#define SORT_SIZE 1
#define SORT_DATE 2
    static int sortDescending;
    static int sortOrder;

    static void initialize()
    {
        srand(time(0));
// HOME not available in /etc/rc.local
#define SETTINGS_DIR "/root"
#define SETTINGS_FILE ".webphone.rc"
//      char *home = getenv("HOME");
        char string[TEXTLEN];
        sprintf(string, "%s/%s", SETTINGS_DIR, SETTINGS_FILE);
        FILE *fd = fopen(string, "r");

        if(!fd)
        {
            printf("Stuff::initialize %d: Couldn't open %s for reading\n", 
                __LINE__, 
                string);
            return;
        }

        while(!feof(fd))
        {
            if(!fgets(string, TEXTLEN, fd)) break;
    // get 1st non whitespace character
            char *key = string;
            while((*key == ' ' ||
                *key == '\t' ||
                *key == '\n') && 
                *key != 0)
            {
                key++;
            }

    // comment or empty
            if(*key == '#' || *key == 0)
            {
                continue;
            }

    // get start of value
            char *value = key;
            while(*value != ' ' && 
                *value != '\t' && 
                *value != '\n' && 
                *value != 0)
            {
                value++;
            }


            while((*value == ' ' ||
                *value == '\t' ||
                *value == '\n') && 
                *value != 0)
            {
                *value = 0;
                value++;
            }

            if(*value == 0)
            {
    // no value given
                continue;
            }

    // delete the newline
            char *end = value;
            while(*end != '\n' && 
                *end != 0)
            {
                end++;
            }

            if(*end == '\n')
            {
                *end = 0;
            }

//            printf("load_defaults %d key='%s' value='%s'\n", __LINE__, key, value);
            if(!strcasecmp(key, "SORTDESCENDING"))
            {
                sortDescending = atoi(value);
            }
            else
            if(!strcasecmp(key, "SORTORDER"))
            {
                sortOrder = atoi(value);
            }
        }

        fclose(fd);
    }

    static void saveDefaults()
    {
        char string[TEXTLEN];
        sprintf(string, "%s/%s", SETTINGS_DIR, SETTINGS_FILE);
        FILE *fd = fopen(string, "w");

        if(!fd)
        {
            printf("Stuff::saveDefaults %d: Couldn't open %s for writing\n", 
                __LINE__, 
                string);
            return;
        }
        
        fprintf(fd, "SORTDESCENDING %d\n", sortDescending);
        fprintf(fd, "SORTORDER %d\n", sortOrder);
        fclose(fd);
    }

};

int Stuff::sortDescending = 0;
int Stuff::sortOrder = SORT_PATH;

// synchronous FIFO
#define FIFOSIZE 65536
class BufferedInputStream
{
public:
    int fd;
    uint8_t fifo[FIFOSIZE];
    uint8_t temp[FIFOSIZE];
    int in_ptr;
    int out_ptr;
    int size;
    int total;

    BufferedInputStream(int fd)
    {
        this->fd = fd;
        in_ptr = 0;
        out_ptr = 0;
        size = 0;
        total = 0;
    }

// return 1 if EOF
    int fill()
    {
        int fragment = FIFOSIZE - size;
        int bytes_read = ::read(fd, temp, fragment);
//printf("BufferedInputStream::fill %d this=%p fd=%d bytes_read=%d fragment=%d\n", 
//__LINE__, this, fd, bytes_read, fragment);
        if(bytes_read <= 0) return 1;

        total += bytes_read;
        for(int i = 0; i < bytes_read; i += fragment)
        {
            fragment = bytes_read - i;
            if(in_ptr + fragment > FIFOSIZE)
                fragment = FIFOSIZE - in_ptr;
            memcpy(fifo + in_ptr, temp + i, fragment);
            in_ptr += fragment;
            if(in_ptr >= FIFOSIZE) in_ptr = 0;
        }
        size += bytes_read;
//printf("BufferedInputStream::fill %d fragment=%d bytes_read=%d size=%d total=%d\n", 
//__LINE__, fragment, bytes_read, size, total);
        return 0;
    }

// returns -1 at the EOF
    int readChar()
    {
        if(size <= 0 && fill()) return -1;

        int result = fifo[out_ptr++];
        if(out_ptr >= FIFOSIZE)
            out_ptr = 0;
        size--;
        return result;
    }

// read at most FIFOSIZE bytes & return the amount read
    int read(uint8_t *dst, int bytes)
    {
//printf("BufferedInputStream::read %d bytes=%d size=%d\n", 
//__LINE__, bytes, size);
        if(size < bytes) fill();
        if(size < bytes) bytes = size;
        int fragment;
        for(int i = 0; i < bytes; i += fragment)
        {
            fragment = bytes - i;
            if(out_ptr + fragment > FIFOSIZE)
                fragment = FIFOSIZE - out_ptr;
            memcpy(dst + i, fifo + out_ptr, fragment);
            out_ptr += fragment;
            if(out_ptr >= FIFOSIZE) out_ptr = 0;
        }
//printf("BufferedInputStream::read %d bytes=%d size=%d\n", 
//__LINE__, bytes, size);
        size -= bytes;
        return bytes;
    }

// rewind the output pointer
    void rewind(int bytes)
    {
        out_ptr -= bytes;
        size += bytes;
        while(out_ptr < 0) out_ptr += FIFOSIZE;
    }

    void readLine(std::string *result)
    {
        int len = 0;
        result->clear();
        while(1)
        {
            int c = readChar();
//printf("BufferedInputStream::readLine %d c=%d\n", __LINE__, c);
            if(c < 0) break;
            if(c == '\n') 
            {
                result->push_back(c);
                break;
            }
// strip \r
            if(c != '\r') result->push_back(c);
        }
    }
};

class WebServerThread
{
public:
    int busy;
    int connection;
    sem_t lock;
    char host[TEXTLEN];
    
    WebServerThread()
    {
        busy = 0;
        connection = -1;
        sem_init(&lock, 0, 0);
        pthread_t tid;
	    pthread_attr_t attr;
	    pthread_attr_init(&attr);
   	    pthread_create(&tid, 
		    &attr, 
		    entrypoint, 
		    this);
    }
    
    void startConnection(int connection)
    {
        this->connection = connection;
        busy = 1;
        sem_post(&lock);
    }
    
    static void* entrypoint(void *ptr)
    {
        WebServerThread *thread = (WebServerThread*)ptr;
        thread->run();
        return 0;
    }
    
    void run()
    {
        while(1)
        {
            sem_wait(&lock);
            printf("WebServerThread::run %d started connection=%d\n", 
                __LINE__,
                connection);

            BufferedInputStream in(connection);
            std::string request;
            in.readLine(&request);
// GET / HTTP/1.1
            printf("WebServerThread::run %d request=%s\n", 
                __LINE__, request.c_str());

            std::string path;
            std::string req;

// get the command.  Don't care enough to use iterator
            const char *ptr = request.c_str();
            while(*ptr != 0 && *ptr != ' ')
                req.push_back(*ptr++);

// skip space
            while(*ptr != 0 && *ptr == ' ') ptr++;

// get path
            while(*ptr != 0 && *ptr != ' ')
                path.push_back(*ptr++);

            printf("WebServerThread::run %d: req=%s path=%s\n", 
                __LINE__, req.c_str(), path.c_str());

// handle a sort command.  Android doesn't allow ? in regular filenames
            int sort_index = path.find("?");
            if(sort_index != std::string::npos)
            {
                char sort_command[TEXTLEN];
                strcpy(sort_command, path.c_str() + sort_index + 1);
                path.erase(sort_index);
                printf("WebServerThread::run %d: sort_command=%s\n",
                    __LINE__,
                    sort_command);
                if(!strncmp(sort_command, "sort", 4))
                {
                    Stuff::sortOrder = sort_command[5] - '0';
                    Stuff::sortDescending = sort_command[7] - '0';
                    Stuff::saveDefaults();
                }
            }

// strip ending /
            while (path.length() > 1 &&
                path[path.length() - 1] == '/') 
            {
                path.erase(path.length() - 1, path.length());
            }

//                printf("WebServerThread::run %d: path=%s\n", __LINE__, path);

// get the file
            if(!req.compare("GET")) 
            {
// flush the socket to avoid a disconnection by peer
                while(true)
                {
                    //Stuff.log("x", "WebServerThread.run 1");
                    std::string line;
                    in.readLine(&line);
//                        printf("WebServerThread::run %d: line=%s", __LINE__, line);
                    if(line.empty() || line[0] == '\n')
                    {
                        break;
                    }

                    if(line.find("Host:") == 0)
                    {
                        strcpy(host, line.c_str() + 5);
                    }
                }
                printf("WebServerThread::run %d: done\n", __LINE__);

                sendFiles(&path);
            }
            else
// handle a form
            if(!req.compare("POST"))
            {
//printf("WebServerThread::run %d\n", __LINE__);
                handlePost(&path, &in);
            }

            printf("WebSesrverThread::run %d: finished\n", __LINE__);
            close(connection);
            busy = false;
        }
    }
    
    void substring(char *dst, char *src, int start, int end)
    {
        if(end > start)
        {
            memcpy(dst, src + start, end - start);
            dst[end - start] = 0;
        }
        else
            dst[0] = 0;
    }
    
    static uint8_t hex_to_i(int c)
    {
        c = tolower(c);
        if(c >= '0' && c <= '9') return c - '0';
        return 10 + c - 'a';
    }

// convert % codes to UTF-8
    void decode_url(std::string *dst, std::string *src)
    {
        int out = 0;
        int in = 0;
        int src_len = src->length();
        dst->clear();
        while(in < src_len)
        {
// + made the titanic directory fail
//             if(src->at(in) == '+')
//             {
//                 dst->push_back(' ');
//                 in++;
//             }
//             else
            if(src->at(in) == '%' && in < src_len - 2)
            {
                uint8_t value = (hex_to_i(src->at(in + 1)) << 4) |
                    (hex_to_i(src->at(in + 2)));
                dst->push_back(value);
                in += 3;
            }
            else
                dst->push_back(src->at(in++));
        }
    }

// convert &#...; codes to UTF-8
    void decodeHTML(std::string *dst, std::string *src)
    {
#define GET_CODE1 0
#define GET_CODE2 1
#define GET_NUMBER 2
        int state = GET_CODE1;
        dst->clear();
        std::string number;
        std::vector<uint32_t> intstring;
//printf("decodeHTML %d: %s\n", __LINE__, src->c_str());
// convert all the codes into integer values in a new intstring
        for(int i = 0; i < src->length(); i++)
        {
            uint32_t c = (uint8_t)src->at(i);
            switch(state)
            {
                case GET_CODE1:
                    if(c == '&') 
                        state = GET_CODE2;
                    else
                        intstring.push_back(c);
                    break;
                case GET_CODE2:
                    if(c == '#') 
                    {
                        state = GET_NUMBER;
                        number.clear();
                    }
                    else
                    {
                        state = GET_CODE1;
                        intstring.push_back(c);
                    }
                    break;
                case GET_NUMBER:
                    if(c == ';') 
                    {
                        state = GET_CODE1;
                        uint32_t value = atoi(number.c_str());
                        intstring.push_back(value);
//printf("decodeHTML %d: %d\n", __LINE__, value);
                    }
                    else
                        number.push_back(c);
                    break;
            }   
        }

// convert all the integer values into binary UTF-8
        for(int i = 0; i < intstring.size(); i++)
        {
            uint32_t c = intstring.at(i);

//printf("decodeHTML %d: c=%d\n", __LINE__, c);
            if(c < 128)
            {
// pass through all below 128
                dst->push_back(c);
            }
            else
//             if(c < 256 &&
//                 (i >= intstring.size() - 1 ||
//                 intstring.at(i + 1) < 128))
//             {
// // HACK: pass through a code < 256 not followed by another code > 127
//                 dst->push_back(c);
//             }
//             else
            {
// UTF-8 encode
                int bits = 0;
                for(int j = 0; j < 32; j++)
                    if((c & (1 << j))) bits = j + 1;

//printf("decodeHTML %d: number=%s value=%d bits=%d\n", 
//__LINE__, number.c_str(), value, bits);
                if(bits > 16)
                {
                    dst->push_back(0b11110000 | (c >> 18));
                    dst->push_back(0b10000000 | ((c >> 12) & 0b00111111));
                    dst->push_back(0b10000000 | ((c >> 6) & 0b00111111));
                    dst->push_back(0b10000000 | (c & 0b00111111));
                }
                else
                if(bits > 11)
                {
                    dst->push_back(0b11100000 | (c >> 12));
                    dst->push_back(0b10000000 | ((c >> 6) & 0b00111111));
                    dst->push_back(0b10000000 | (c & 0b00111111));
                }
                else
                if(bits > 7)
                {
                    dst->push_back(0b11000000 | (c >> 6));
                    dst->push_back(0b10000000 | (c & 0b00111111));
                }
                else
                {
                    dst->push_back(c);
                }
            }   
        }
    }

// convert UTF-8 to HTML escape codes.  Append to htmlEncodedName
    void encodeHTML(std::string *output, const char *src, int is_href)
    {
        int len = strlen(src);
        for(int in = 0; in < len; in++)
        {
            uint32_t code = (uint8_t)src[in];
            if((code & 0x80) || code == '<' || code == '>')
            {
                if(is_href)
                {
// convert UTF-8 to % code
                    const char *hex = "0123456789abcdef";

                    output->push_back('%');
                    output->push_back(hex[(code & 0xf0) >> 4]);
                    output->push_back(hex[(code & 0x0f)]);
                }
                else
                {
// convert UTF-8 to &# codes
// pass through continuation code with no start code
                    if((code & 0b11000000) == 0b10000000 ||
// pass through start code with no continuation code
                        ((code & 0x80) && 
                        (in >= len - 1 || (src[in + 1] & 0b11000000) != 0b10000000)))
                    {
                        ;
                    }
                    else
// 4 byte code
                    if((code & 0b11111000) == 0b11110000 && in < len - 3)
                    {
                        code = ((code & 0b00000111) << 18) |
                            ((((uint32_t)src[in + 1]) & 0b00111111) << 12) |
                            ((((uint32_t)src[in + 2]) & 0b00111111) << 6) |
                            (((uint32_t)src[in + 3]) & 0b00111111);
                        in += 3;
                    }
                    else
// 3 byte code
                    if((code & 0b11110000) == 0b11100000 && in < len - 2)
                    {
                        code = ((code & 0b00001111) << 12) |
                            ((((uint32_t)src[in + 1]) & 0b00111111) << 6) |
                            (((uint32_t)src[in + 2]) & 0b00111111);
                        in += 2;
                    }
                    else
// 2 byte code
                    if((code & 0b11100000) == 0b11000000 && in < len - 1)
                    {
                        code = ((code & 0b00011111) << 6) |
                            (((uint32_t)src[in + 1]) & 0b00111111);
                        in += 1;
                    }

                    char string2[TEXTLEN];
                    sprintf(string2, "&#%d;", code);
                    output->append(string2);
                }
            }
            else
                output->push_back(code);
        }
    }

    


    static int path_ascending(const void *ptr1, const void *ptr2)
    {
        return strcasecmp((*(DirEntry**)ptr1)->name.c_str(), (*(DirEntry**)ptr2)->name.c_str());
    }

    static int path_descending(const void *ptr1, const void *ptr2)
    {
        return strcasecmp((*(DirEntry**)ptr2)->name.c_str(), (*(DirEntry**)ptr1)->name.c_str());
    }

    static int size_ascending(const void *ptr1, const void *ptr2)
    {
        return (*(DirEntry**)ptr1)->size >= (*(DirEntry**)ptr2)->size;
    }

    static int size_descending(const void *ptr1, const void *ptr2)
    {
        return (*(DirEntry**)ptr1)->size <= (*(DirEntry**)ptr2)->size;
    }

    static int date_ascending(const void *ptr1, const void *ptr2)
    {
        return (*(DirEntry**)ptr1)->date >= (*(DirEntry**)ptr2)->date;
    }

    static int date_descending(const void *ptr1, const void *ptr2)
    {
        return (*(DirEntry**)ptr1)->date <= (*(DirEntry**)ptr2)->date;
    }


// send text to the socket
    void print(const char *string)
    {
        int _ = write(connection, string, strlen(string));
    }

    void errorReport(const char *code, const char *title, const char *msg)
    {
        char string[TEXTLEN];
        sprintf(string, 
            "HTTP/1.0 %s %s\r\n"
            "\r\n"
            "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\r\n"
            "<TITLE>%s %s</TITLE>\r\n"
            "</HEAD><BODY>\r\n"
            "<H1>%s</H1>\r\n%s<P>\r\n"
            "<HR><ADDRESS>Webphone at %s</ADDRESS>\r\n"
            "</BODY></HTML>\r\n",
            code,
            title,
            code,
            title,
            title,
            msg,
            host);
        print(string);
    }

    void sendHeader(std::string *output, const char *contentType, int64_t size)
    {
        char string[TEXTLEN];
        output->append("HTTP/1.0 200 OK\r\n"
            "Content-Type: ");
        output->append(contentType);
        output->append("\r\n");
            
//printf("sendHeader %d: %lld\n", __LINE__, (long long)size);
        if(size > 0)
        {
            sprintf(string, "Content-Length: %lld\r\n", (long long)size);
//printf("sendHeader %d: %s\n", __LINE__, string);
            output->append(string);
        }

        time_t now = time(0);
        struct tm *tm_info = localtime(&now);
        strftime(string, TEXTLEN, "%a %b %d %H:%M:%S %Z %Y", tm_info);
        output->append("Date: ");
        output->append(string);
        output->append("\r\n"
            "Server: WebPhone\r\n\r\n");
    }

    void sendHeader(const char *contentType, int64_t size)
    {
        std::string output;
        sendHeader(&output, contentType, size);
        print(output.c_str());
    }

    static int ends_with(const char *str, const char *suffix) 
    {
        if (!str || !suffix) return 0;

        int len_str = strlen(str);
        int len_suffix = strlen(suffix);

        if (len_suffix > len_str) return 0;

        return !strcmp(str + len_str - len_suffix, suffix);
    }
    
    static int starts_with(const char *haystack, const char *needle)
    {
        return !strncmp(haystack, needle, strlen(needle));
    }

    static const char* guessContentType(const char *path)
    {
        if (ends_with(path, ".class"))
            return "application/octet-stream";
        else if (ends_with(path, ".html") || ends_with(path, ".htm"))
            return "text/html";
        else if (ends_with(path, ".gif"))
            return "image/gif";
        else if (ends_with(path, ".gz"))
            return "application/gzip";
        else if (ends_with(path, ".jpg") || ends_with(path, ".jpeg"))
            return "image/jpeg";
        else if (ends_with(path, ".js"))
            return "application/javascript";
        else if (ends_with(path, ".mp3"))
            return "audio/mpeg3";
        else if (ends_with(path, ".m4a"))
            return "audio/mp4";
        else if (ends_with(path, ".mp4") || ends_with(path, ".m4v"))
            return "video/mp4";
        else if (ends_with(path, ".pdf"))
            return "application/pdf";
        else if (ends_with(path, ".png"))
            return "image/png";
        else
            return "text/plain";
    }

    const char* sortButton(int field, std::string *path)
    {
        static const char* sortText[] = 
        {
            "path", "PATH", 
            "size", "SIZE", 
            "date", "DATE" 
        };
        static char result[TEXTLEN];
        sprintf(result, "<TD><B>");
        const char *directionText = "";
        if(Stuff::sortOrder == field)
        {
            if(Stuff::sortDescending) 
                directionText = "_0";
            else
                directionText = "_1";
        }
        else
        {
            if(Stuff::sortDescending) 
                directionText = "_1";
            else
                directionText = "_0";
        }
        
        char string2[TEXTLEN];
        sprintf(string2, 
            "<A HREF=\"%s?sort_%d%s\">",
            path->c_str(),
            field,
            directionText);
        strcat(result, string2);
        if(Stuff::sortOrder == field)
        {
            strcat(result, sortText[field * 2 + 1]);
            if(Stuff::sortDescending)
                strcat(result, "&#8593;");
            else
                strcat(result, "&#8595;");
        }
        else
        {
            strcat(result, sortText[field * 2]);
        }

        strcat(result, "</A></TD>\r\n");
        return result;
    }

    void sendFile(const char *path)
    {
        FILE *in = fopen(path, "r");
        if(!in)
        {
            printf("WebServerThread::sendFile %d: couldn't open %s\n", __LINE__, path);
        }
        else
        {
            uint8_t buffer[65536];
            while(1)
            {
                int bytes_read = fread(buffer, 1, sizeof(buffer), in);
                if(bytes_read > 0)
                {
                    if(write(connection, buffer, bytes_read) <= 0)
                    {
                        printf("WebServerThread::sendFile %d: error writing to socket\n",
                            __LINE__);
                        break;
                    }
                }
                else
                    break;
            }
        }
    }


    void sendFiles(std::string *path)
    {
        char string[TEXTLEN];
        std::string decodedPath;
        decode_url(&decodedPath, path);
        printf("WebServerThread::sendFiles %d: path=%s decodedPath=%s\n",
            __LINE__, path->c_str(), decodedPath.c_str());
        struct stat ostat;
        if(!stat(decodedPath.c_str(), &ostat))
        {
            if(S_ISDIR(ostat.st_mode))
            {
// send the big directory listing
                DIR *dirstream = opendir(decodedPath.c_str());
                if(dirstream)
                {
                    struct dirent64 *new_filename;
                    std::vector<DirEntry*> files;
                    while((new_filename = readdir64(dirstream)))
                    {
// skip some files
                        if(!strcmp(new_filename->d_name, ".") ||
                            !strcmp(new_filename->d_name, "..")) continue;

                        DirEntry *entry = new DirEntry(&decodedPath, new_filename->d_name);
                        files.push_back(entry);
                    }

//                     printf("WebServerThread::sendFiles %d: size=%d %s\n", 
//                         __LINE__, 
//                         (int)files.size(),
//                         files[0]->name.c_str());

#define SORT_MACRO(compare) \
    qsort(&files[0], files.size(), sizeof(DirEntry*), compare)
                    switch(Stuff::sortOrder)
                    {
                        case SORT_PATH:
                            if(Stuff::sortDescending)
                                SORT_MACRO(path_descending);
                            else
                                SORT_MACRO(path_ascending);
                            break;
                        case SORT_DATE:
                            if(Stuff::sortDescending)
                                SORT_MACRO(date_descending);
                            else
                                SORT_MACRO(date_ascending);
                            break;
                        case SORT_SIZE:
                            if(Stuff::sortDescending)
                                SORT_MACRO(size_descending);
                            else
                                SORT_MACRO(size_ascending);
                            break;
                    }
//                     printf("WebServerThread::sendFiles %d: size=%d %s\n", 
//                         __LINE__, 
//                         (int)files.size(),
//                         files[0]->name.c_str());

                    sendHeader("text/html", -1);

// don't underline links
                    print("<style>\n"
                        "a {\n"
                        "    text-decoration: none;\n"
                        "}\n"
                        "</style>\n\n");


                    sprintf(string, 
                        "<B>Index of %s</B><BR>\r\n",
                        decodedPath.c_str());
                    print(string);
                    sprintf(string,
                        "<A HREF=\"%s\"> <B>RELOAD </B></A><BR>\r\n",
                        path->c_str());
                    print(string);


// create the .. entry.  Path had all trailing / stripped already
                    if(path->compare("/") != 0) 
                    {
                        std::string truncated;
                        truncated.assign(*path);
                        int offset = truncated.rfind('/');
                        if(offset != std::string::npos) truncated.erase(offset + 1);

//                         printf("WebServerThread::sendFiles %d: truncated=%s\n", 
//                             __LINE__, truncated.c_str());

                        std::string output;
//                        output.append("<TR><TD></TD><TD></TD><TD>");
                        output.append("<A HREF=\"");
                        output.append(truncated.c_str());
                        output.append("\">"
                            "<B>PARENT DIR</B></A>");
//                        output.append("</TD></TR>\r\n");
                        output.append("<BR>\r\n");
                        print(output.c_str());
                    }

// must always encode in multipart data in case the filename has a ?
// the order of the widgets determines the order of the form data
                    print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
                    print("Upload files to this directory:<BR>\n");
                    print("<INPUT TYPE=\"file\" NAME=\"__UPLOAD\" MULTIPLE=\"true\">\n");
                    print("<INPUT TYPE=\"submit\" VALUE=\"UPLOAD\" NAME=\"__UPLOAD\">\n");
                    print("</FORM>\r\n");

                    print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
                    print("Create a directory:<BR>\n");
                    print("<INPUT TYPE=\"text\" NAME=\"__MKDIRPATH\">\n");
                    print("<BUTTON TYPE=\"submit\" VALUE=\"__MKDIR\" NAME=\"__MKDIR\">MAKE DIRECTORY</BUTTON><BR>\n");
                    print("</FORM>\r\n");


                    print("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\">\r\n");

                    print("Move the selected files to another directory:<BR>\n");
                    print("<INPUT TYPE=\"text\" NAME=\"__MOVEPATH\">\n");
                    print("<BUTTON TYPE=\"submit\" VALUE=\"__MOVE\" NAME=\"__MOVE\">MOVE FILES</BUTTON><P>\n");

                    print("<BUTTON TYPE=\"submit\" VALUE=\"__DELETE\" NAME=\"__DELETE\">DELETE</BUTTON>\n");
                    print("<BUTTON TYPE=\"submit\" VALUE=\"__RENAME\" NAME=\"__RENAME\">RENAME</BUTTON>\n");
                    print("<BUTTON TYPE=\"submit\" VALUE=\"__EDIT\" NAME=\"__EDIT\">EDIT</BUTTON>\n");
                    print("<BUTTON TYPE=\"button\" onclick=\"selectAll()\">CHECK ALL</button>\n");
                    print("<TABLE>\r\n");
// create the sort options
                    print("<TR>\r\n");
                    print(sortButton(SORT_SIZE, path));
                    print(sortButton(SORT_DATE, path));
                    print(sortButton(SORT_PATH, path));

                    print("</TR>\r\n");
                    print("<TR><TD style=\"height: 1px;\" bgcolor=\"000000\" COLSPAN=3></TD></TR>\r\n");


                    for(int i = 0; i < files.size(); i++)
                    {
                        DirEntry *file = files[i];
                        char formattedDate[TEXTLEN];
                        struct tm *tm_info = localtime(&file->date);
                        strftime(formattedDate, TEXTLEN, "%m/%d/%Y %H:%M", tm_info);
                        
                        std::string printedName;
                        std::string checkboxName;
                        std::string linkPath;
//printf("sendfiles %d: %s ", __LINE__, file->name.c_str());
//for(int j = 0; j < file->name.length(); j++) printf("%02x ", (uint8_t)file->name.at(j));
//printf("\n");
                        encodeHTML(&printedName, file->name.c_str(), 0);
                        checkboxName.assign(printedName);
                        encodeHTML(&linkPath, file->path.c_str(), 1);
                        
                        std::string textBegin;
                        if(file->isLink) textBegin.append("<I>");
                        
                        if(file->isDir)
                        {
                            textBegin.append("<B>");
                            printedName.append("/");
                        }

                        textBegin.append("<A HREF=\"");
                        textBegin.append(linkPath);
                        textBegin.append("\">");

                        if(file->isLink)
                        {
                            char *resolved = realpath(file->path.c_str(), 0);
                            printedName.append(" -> ");
                            encodeHTML(&printedName, resolved, 0);
                            free(resolved);
                        }

                        std::string output;
// size
                        output.assign("<TR><TD>");
                        output.append(textBegin);
                        sprintf(string, "%lld", (long long)file->size);
                        output.append(string);

// date                        
                        output.append("</TD><TD>");
                        output.append(textBegin);
                        output.append(formattedDate);

// checkbox
                        output.append("</TD><TD><INPUT TYPE=\"checkbox\" CLASS=\"item\" ID=\"a\" NAME=\"");
                        output.append(checkboxName);
                        output.append("\" VALUE=\"");
                        output.append(CHECKED);
                        output.append("\">");

// filename
                        output.append(textBegin);
                        output.append(printedName);
                        output.append("\n");
                        
                        print(output.c_str());
                    }


                    print("</TABLE>\r\n"
                        "</FORM>\r\n\r\n"
                        "<script>\n"
                        "    // Function to check/uncheck all checkboxes with class \"item\"\n"
                        "    function selectAll() {\n"
                        "        const checkboxes = document.querySelectorAll('.item');\n"
                        "        var checked = false;\n"
                        "        if(checkboxes.length > 0)\n"
                        "            checked = checkboxes[0].checked;\n"
                        "        checkboxes.forEach(checkbox => {\n"
                        "            checkbox.checked = !checked;\n"
                        "        });\n"
                        "    }\n"
                        "\r\n\r\n"
                        "</script>");

                    for(int i = 0; i < files.size(); i++) delete files[i];
                }
                else
                {
                    char string[TEXTLEN];
                    sprintf(string, 
                        "Couldn't access that directory.  " 
                        "How about going to this award winning page:<P>" 
                        "<A HREF=\"http://%s/sdcard\">%s/sdcard</A>",
                        host,
                        host);

                    errorReport("404", "WebServerThread::sendFiles",
                        string);
                }
                
            }
            else
            {
// send the file
                sendHeader(guessContentType(decodedPath.c_str()), ostat.st_size);
                sendFile(decodedPath.c_str());
            }
        }
        else
        {
            printf("WebServerThread::sendFiles %d: %s not found\n", 
                __LINE__, decodedPath.c_str());
            errorReport("404", "WebServerThread.sendFiles",
                    "The requested URL was not found on this server.");
        }
    }


    void getBoundary(char *dst, BufferedInputStream *in)
    {
        std::string string;
        dst[0] = 0;
        while(1)
        {
            in->readLine(&string);
            if(string.length() == 0)
            {
                printf("WebServerThread::getBoundary %d: no boundary", __LINE__);
                return;
            }

//printf("WebServerThread::getBoundary %d: %s\n", __LINE__, string);
            int offset;
            if((offset = string.find("boundary=")) != std::string::npos)
            {
                offset += 9;
                char *ptr2 = dst;
                while(offset < string.length() && 
                    string[offset] != '\n' && 
                    string[offset] != '\r')
                    *ptr2++ = string[offset++];
                *ptr2 = 0;
//printf("WebServerThread::getBoundary %d: dst=%s\n", __LINE__, dst);
                return;
            }
        }
    }


// return 1 if end of post
    int skipBoundary(const char *boundary, BufferedInputStream *in)
    {
        std::string string;
        char eof_boundary[TEXTLEN];
        sprintf(eof_boundary, "--%s--", boundary);
        while(true)
        {
            in->readLine(&string);
            if(string.length() == 0 || string.find(eof_boundary) != std::string::npos) return 1;
            if(string.find(boundary) != std::string::npos) return 0;
        }
    }

    void getContentDisposition(ContentDisposition *result,
        BufferedInputStream *in,
        const char *boundary)
    {
        char eof_boundary[TEXTLEN];
        sprintf(eof_boundary, "--%s--", boundary);
        std::string text;

        while(1)
        {
            in->readLine(&text);
            if(text.empty() || text.find(eof_boundary) != std::string::npos) return;
            if(text.find("Content-Disposition:") == 0)
            {
//printf("WebServerThread::getContentDisposition %d: %s\n", __LINE__, text.c_str());
                int offset = text.find("filename=\"");
                if(offset != std::string::npos)
                {
// get the filename
                    std::string filename;
                    offset += 10;
                    while(offset < text.length() && text[offset] != '\"') 
                        filename.push_back(text[offset++]);
                    result->filename.assign(filename);
                }

// get the name
                offset = text.find("name=\"");
                if(offset != std::string::npos)
                {
                    std::string name;
                    offset += 6;
                    while(offset < text.length() && text[offset] != '\"')
                        name.push_back(text[offset++]);
                    result->name.assign(name);
                }
                
                return;
            }
        }
    }

    void getContentValue(ContentValue *result,
        BufferedInputStream *in,
        const char *boundary)
    {
        std::string line;
        std::string value;
        char eof_boundary[TEXTLEN];
        sprintf(eof_boundary, "--%s--", boundary);
// skip the 1st line
        in->readLine(&line);
        while(1)
        {
            in->readLine(&line);
            if(line.find(boundary) == std::string::npos)
                value.append(line);
            else
            {
//printf("WebServerThread::getContentValue %d: %s\n", __LINE__, value.c_str());
// delete the last newline
                if(value.length() > 0 &&
                    value[value.length() - 1] == '\n')
                    value.erase(value.length() - 1);
                result->value.assign(value);
                if(line.find(eof_boundary) != std::string::npos)
                    result->eof = 1;
                break;
            }
        }
    }
    
    int getData(const char *boundary, BufferedInputStream *in, FILE *fd)
    {
// boundary for data has 4 extra starting bytes
        char boundary2[TEXTLEN];
        sprintf(boundary2, "\r\n--%s", boundary);
        int boundary2_length = strlen(boundary2);
        
// skip Content-Type & empty line
        std::string text;
        in->readLine(&text);
        if(text.find("Content-Type:") != std::string::npos)
            in->readLine(&text);

// read until boundary
        uint8_t fifo[FIFOSIZE];
        int fifo_size = 0;
        int error = 0;
        while(1)
        {
            int maxRead = FIFOSIZE - fifo_size;
            int read_result = in->read(fifo + fifo_size, maxRead);
//printf("WebServerThread::getData %d: read_result=%d\n", __LINE__, read_result);
            if(read_result <= 0)
            {
                printf("WebServerThread::getData %d: read failed\n", __LINE__);
                error = 1;
                break;
            }

            fifo_size += read_result;
// test fifo for boundary
            int score = 0;
            int gotBoundary = 0;
// end of boundary in the fifo buffer
            int boundaryEnd = 0;
            for(int i = 0; i < fifo_size; i++)
            {
                if(fifo[i] == boundary2[score])
                {
                    score++;
                    if(score >= boundary2_length)
                    {
                        gotBoundary = 1;
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

//printf("WebServerThread::getData %d: gotBoundary=%d\n", __LINE__, gotBoundary);
// rewind the input to the end of the boundary code
            if(gotBoundary)
            {
                in->rewind(fifo_size - boundaryEnd);
                fifo_size = boundaryEnd;
            }


// at most, maxWrite can be written
// if we didn't get the boundary, eat the 1st byte of the boundary size to advance
// the scan window
            int maxWrite = fifo_size - boundary2_length;
            if(!gotBoundary) maxWrite += 1;
            if(maxWrite > 0)
            {
                int write_result = fwrite(fifo, 1, maxWrite, fd);
                if(write_result < maxWrite)
                {
                    printf("WebServerThread::getData %d: write failed\n", __LINE__);
                    error = 1;
                    break;
                }
                
                if(gotBoundary)
                {
                    fifo_size = 0;
                    break;
                }
                else
                {
// drain the fifo
                    memmove(fifo, fifo + maxWrite, fifo_size - maxWrite);
                    fifo_size -= maxWrite;
                }
            }
        }
        return error;
    }
    
    void doUpload(std::string *path, 
        std::multimap<std::string, std::string> *files)
    {
// rename the temp files
        int failed = 0;
        for(auto i = files->begin(); i != files->end(); i++)
        {
            const char *filename = i->first.c_str();
            const char *tempPath = i->second.c_str();
            std::string newPath;
            newPath.assign(*path);
            newPath.push_back('/');
            newPath.append(filename);
            printf("WebServerThread::doUpload %d: renaming %s to %s\n",
                __LINE__,
                tempPath,
                newPath.c_str());
            rename(tempPath, newPath.c_str());
        }

// send the directory
        if(!failed) sendFiles(path);
    }

    void doMkdir(std::string *path, 
        std::string *name)
    {
        std::string newPath;
        newPath.assign(*path);
        newPath.push_back('/');
        newPath.append(*name);
        if(mkdir(newPath.c_str(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH))
        {
            char string[TEXTLEN];
            sprintf(string, "Couldn't create the directory %s", newPath.c_str());
            errorReport("404", "WebServerThread::doMkdir",
                string);
        }
        else
        {
            sendFiles(path);
        }
    }
    
    void confirmDelete(std::string *path, 
        std::vector<std::string> *fileList)
    {
        std::string output;
        std::string string;
        sendHeader(&output, "text/html", -1);
        output.append("<B>Really delete the following files in ");
        output.append(*path);
        output.append("?</B><P>\r\n");
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            encodeHTML(&output, i->c_str(), 0);
            output.append("<BR>\r\n");
        }

        output.append("<P>\r\n");
        output.append("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
        output.append("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMDELETE\" NAME=\"__CONFIRMDELETE\">DELETE</BUTTON>\n");
        output.append("<BUTTON TYPE=\"submit\" VALUE=\"__ABORTDELETE\" NAME=\"__ABORTDELETE\">DON'T DELETE</BUTTON><P>\n");


// resend the file list
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            output.append("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"");
            encodeHTML(&output, i->c_str(), 0);
            output.append("\" value=\"");
            output.append(CHECKED);
            output.append("\">\r\n");
        }

        output.append("</FORM>\r\n");
        print(output.c_str());
    }

    void deleteFiles(std::string *path, 
        std::vector<std::string> *fileList)
    {
        int error = 0;
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            std::string fullPath;
            fullPath.assign(*path);
            fullPath.push_back('/');
            fullPath.append(*i);
            printf("WebServerThread::deleteFiles %d: %s\n",
                __LINE__,
                fullPath.c_str());
            if(remove(fullPath.c_str()))
            {
                std::string string;
                string.append("Couldn't delete ");
                string.append(fullPath);
                errorReport("404", "WebServerThread::deleteFiles",
                    string.c_str());
                error = 1;
                break;
            }
        }

        if(!error)
        {
            sendFiles(path);
        }
    }
    
    void confirmMove(std::string *path, 
        std::vector<std::string> *fileList,
        std::string *movePath)
    {
        std::string output;
        std::string string;
        std::string fullDst;
        sendHeader(&output, "text/html", -1);
        if(movePath->find("/") == 0)
            fullDst.assign(*movePath);
        else
        {
            string.assign(*path);
            string.push_back('/');
            string.append(*movePath);
            encodeHTML(&fullDst, string.c_str(), 0);
        }
        output.append("<B>Really move the following files to ");
        output.append(fullDst);
        output.append("?</B><P>\r\n");
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            encodeHTML(&output, i->c_str(), 0);
            output.append("<BR>\r\n");
        }


        output.append("<P>\r\n");
        output.append("<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n");
// the buttons go first so they get the 1st form part
        output.append("<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMMOVE\" NAME=\"__CONFIRMMOVE\">MOVE</BUTTON>\n");
        output.append("<BUTTON TYPE=\"submit\" VALUE=\"ABORTMOVE\" NAME=\"ABORTMOVE\">DON'T MOVE</BUTTON><P>\n");

// resend the destination
        output.append("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"__MOVEPATH\" value=\"");
        output.append(fullDst);
        output.append("\">\r\n");

// resend the file list
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            output.append("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"");
            encodeHTML(&output, i->c_str(), 0);
            output.append("\" value=\"");
            output.append(CHECKED);
            output.append("\">\r\n");
        }

        output.append("</FORM>\r\n");
        print(output.c_str());
    }

    void moveFiles(std::string *path, 
        std::vector<std::string> *fileList,
        std::string *movePath)
    {
        int error = 0;
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            std::string srcPath;
            srcPath.assign(*path);
            srcPath.append("/");
            srcPath.append(*i);
            std::string dstPath;
            dstPath.assign(*movePath);
            dstPath.append("/");
            dstPath.append(*i);
            printf("WebServerThread::moveFiles %d: %s -> %s\n",
                __LINE__,
                srcPath.c_str(),
                dstPath.c_str());
            if(srcPath.compare(dstPath))
            {
                if(rename(srcPath.c_str(), dstPath.c_str()))
                {
                    std::string string;
                    string.append("Couldn't move ");
                    string.append(srcPath);
                    string.append(" -> ");
                    string.append(dstPath);
                    errorReport("404", "WebServerThread::moveFiles",
                        string.c_str());
                    error = 1;
                    break;
                }
            }
        }
        
        if(!error)
            sendFiles(path);
    }

    void confirmRename(std::string *path, 
        std::vector<std::string> *fileList)
    {
        std::string output;
        sendHeader(&output, "text/html", -1);
        output.append("<style>\r\n"
            ".full-width {\r\n"
            "  width: 100%;\r\n"
            "}\r\n"
            "</style>\r\n");

        output.append("<B>Rename the following files in ");
        output.append(*path);
        output.append("?</B><P>\r\n");
// the buttons go first so they get the 1st form part
        output.append("<P>\r\n"
            "<FORM METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\r\n"
            "<BUTTON TYPE=\"submit\" VALUE=\"__CONFIRMRENAME\" NAME=\"__CONFIRMRENAME\">RENAME</BUTTON>\n"
            "<BUTTON TYPE=\"submit\" VALUE=\"__ABORTRENAME\" NAME=\"__ABORTRENAME\">DON'T RENAME</BUTTON><P>\n");

        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            std::string encodedName;
            encodeHTML(&encodedName, i->c_str(), 0);
//printf("\nWebServerThread::confirmRename %d encodedName=%s\n",
//__LINE__, encodedName.c_str());
//for(int j = 0; j < i->length(); j++) printf("%02x ", (uint8_t)i->at(j));
//printf("\n");
            output.append(encodedName);
            output.append(" -> <BR>"
                "<INPUT TYPE=\"text\" class=\"full-width\" id=\"a\" name=\"");
            output.append(encodedName);
            output.append("\" value=\"");
            output.append(encodedName);
            output.append("\"><BR>\r\n");
        }

// resend the file list
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            output.append("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"");
            encodeHTML(&output, i->c_str(), 0);
            output.append("\" value=\"");
            output.append(CHECKED);
            output.append("\">\r\n");
        }
        
        output.append("</FORM>\r\n");
        print(output.c_str());
    }

    void renameFiles(std::string *path, 
        std::vector<std::string> *fileList,
        std::multimap<std::string, std::string> *content)
    {
        int error = 0;
        for(auto i = fileList->begin(); i != fileList->end(); i++)
        {
            std::string oldName = *i;
//            printf("renameFiles %d oldName=%s\n", __LINE__, oldName.c_str());
            if(content->find(oldName) == content->end())
            {
//                printf("renameFiles %d: couldn't find %s\n", __LINE__, oldName.c_str());
                std::string string;
                string.append("Couldn't find ");
                string.append(oldName.c_str());
                string.append("in ContentDisposition.\r\n");
                errorReport("404", 
                    "WebServerThread::renameFiles",
                    string.c_str());
                error = 1;
                break;
                error = 1;
            }
            std::string newName = content->find(oldName)->second;
            if(oldName.compare(newName))
            {
                std::string oldFile;
                oldFile.assign(*path);
                oldFile.append("/");
                oldFile.append(oldName);
                std::string newFile;
                newFile.assign(*path);
                newFile.append("/");
                newFile.append(newName);
                printf("WebServerThread::renameFiles %d: %s -> %s\n",
                    __LINE__,
                    oldFile.c_str(),
                    newFile.c_str());
                if(rename(oldFile.c_str(), newFile.c_str()))
                {
                    std::string string;
                    string.append("Couldn't rename ");
                    string.append(oldFile);
                    string.append(" -> ");
                    string.append(newFile);
                    errorReport("404", 
                        "WebServerThread::renameFiles",
                        string.c_str());
                    error = 1;
                    break;
                }
            }
        }
        
        if(!error) sendFiles(path);
    }

    void editFile(std::string *path, 
        std::vector<std::string> *fileList,
        int wroteIt)
    {
        if(fileList->size() == 0)
        {
            errorReport("404", 
                "WebServerThread::editFile",
                "No file selected for editing");
            return;
        }
        
        std::string completePath;
        completePath.append(*path);
        completePath.append("/");
        completePath.append(*fileList->begin());

        std::string output;
        sendHeader(&output, "text/html", -1);
        output.append(
            "<style>\n"
            "    html, body {\n"
            "        height: 100%;\n"
            "        margin: 0;\n"
            "        padding: 0;\n"
            "    }\n"
            "    .top {\n"
            "        height: auto;\n"
            "    }\n"
            "    .bottom {\n"
            "        height: auto;\n"
            "    }\n"
            "    .container {\n"
            "        display: flex;\n"
            "        flex-direction: column;\n"
            "        height: 100%;\n"
            "    }\n"
            "    textarea {\n"
            "        flex: 1;\n"
            "        resize: none; /* Optional: prevent resizing */\n"
            "        width: 100%;\n"
            "        height: 100%;\n"
            "        box-sizing: border-box;\n"
            "        white-space: pre;\n"
            "    }\n"
            "</style>\n"


            "<script>\n"
            "    // Function to save the scroll position of the textarea\n"
            "    function saveScrollPosition() {\n"
            "        var textarea = document.getElementById('a');\n"
            "        sessionStorage.setItem('scrollPosition', textarea.scrollTop);\n"
            "    }\n"
            "\n"
            "    // Function to restore the scroll position of the textarea\n"
            "    function restoreScrollPosition() {\n"
            "        var scrollPosition = sessionStorage.getItem('scrollPosition');\n"
            "        if (scrollPosition !== null) {\n"
            "            var textarea = document.getElementById('a');\n"
            "            textarea.scrollTop = scrollPosition;\n"
            "        }\n"
            "    }\n"
            "\n"
            "    // Call the function to restore scroll position after the page has loaded\n"
            "    window.onload = restoreScrollPosition;\n"
            "</script>\n"

            "\n"
            "<BODY>\n"
            "<FORM CLASS=\"container\" METHOD=\"post\" ENCTYPE=\"multipart/form-data\" >\n"
            "<DIV CLASS=\"top\">"
        );

        output.append("<B>Editing ");
        encodeHTML(&output, completePath.c_str(), 0);
        output.append("</B><BR>\r\n");
        output.append("CR's have been stripped<BR>\n");
        if(wroteIt)
        {
            struct stat ostat;
            if(!stat(completePath.c_str(), &ostat))
            {
                char string[TEXTLEN];
                sprintf(string, "%d", (int)ostat.st_size);
                output.append(string);
                output.append(" bytes written<BR>\r\n");
            }
            else
            {
                errorReport("404", 
                    "WebServerThread::editFile",
                    "Error saving file.  Go back.");
                return;
            }
        }
        
        output.append(
            "<BUTTON TYPE=\"submit\" VALUE=\"__EDITSAVE\" NAME=\"__EDITSAVE\">SAVE</BUTTON>\n"
            "</DIV>\n"
            "<TEXTAREA id=\"a\" name=\"__EDITTEXT\"  onscroll=\"saveScrollPosition()\" style=\"white-space: pre;\">\r\n"
        );

// print the file
        FILE *fd = fopen(completePath.c_str(), "r");
        if(!fd)
        {
            errorReport("404", 
                "WebServerThread::editFile",
                "Error reading file.");
            return;
        }
        else
        {
            fseek(fd, 0, SEEK_END);
            int64_t size = ftell(fd);
            fseek(fd, 0, SEEK_SET);
            if(size > 0x100000)
            {
                fclose(fd);
                errorReport("404", 
                    "WebServerThread::editFile",
                    "File too large.");
                return;
            }

            print(output.c_str());
            output.clear();
            char *buffer = (char*)malloc(size + 1);
            int bytes_read = fread(buffer, 1, size, fd);
            if(bytes_read > 0)
            {
// null terminate for HTML converter
                buffer[bytes_read] = 0;
                encodeHTML(&output, buffer, 0);
                int _ = write(connection, output.c_str(), output.length());
                output.clear();
            }
            fclose(fd);
            free(buffer);
        }
        
        output.append(
            "</TEXTAREA>\r\n"
            "<DIV CLASS=\"bottom\">\n"
            "<BUTTON TYPE=\"submit\" VALUE=\"__EDITQUIT\" NAME=\"__EDITQUIT\">QUIT</BUTTON><BR>\n"
        );
// resend the file name
        output.append("<INPUT HIDDEN=\"true\" TYPE=\"text\" id=\"a\" name=\"");
        encodeHTML(&output, fileList->begin()->c_str(), 0);
        output.append("\" value=\"");
        output.append(CHECKED);
        output.append("\">\r\n");

        output.append("</DIV>\n</FORM>\r\n");
        print(output.c_str());
    }

    void editSave(std::string *path, 
        std::vector<std::string> *fileList,
        std::string *text)
    {
        std::string completePath;
        completePath.append(*path);
        completePath.append("/");
        completePath.append(*fileList->begin());
        FILE *fd = fopen(completePath.c_str(), "w");
        int bytes_written = -1;
        if(fd)
        {
            bytes_written = fwrite(text->c_str(), 1, text->length(), fd);
            fclose(fd);
        }

        if(!fd || bytes_written < 0)
        {
            std::string string;
            string.append("Error writing ");
            string.append(completePath);
            errorReport("404", 
                "WebServerThread::editSave",
                string.c_str());
        }
        else
        {
            editFile(path, fileList, 1);
        }
    }


    void handlePost(std::string *path, BufferedInputStream *in)
    {
// convert the post into tables
        char boundary[TEXTLEN];
        getBoundary(boundary, in);
// read up to next boundary
        skipBoundary(boundary, in);
// make a table of all the content, uploaded files, & selected filenames
        std::multimap<std::string, std::string> content;
        std::vector<std::string> fileList;
// map files to temp files
        std::multimap<std::string, std::string> files;
        
        while(1)
        {
            ContentDisposition result;
            getContentDisposition(&result, in, boundary);
            std::string decodedFilename;
            decodeHTML(&decodedFilename, &result.filename);

            if(!result.name.empty())
            {
                if(!result.filename.empty())
                {
// uploaded files have to be written to a temporary or we'll run out of memory
                    std::string tempPath;
                    tempPath.assign(*path);
                    tempPath.append("/.temp");
                    const char *hex = "0123456789abcdef";
                    for(int i = 0; i < 36; i++)
                        tempPath.push_back(hex[rand() % 16]);

                    printf("WebServerThread::handlePost %d: tempPath=%s\n", __LINE__, tempPath.c_str());
                    FILE *fd = fopen(tempPath.c_str(), "w");
                    int error = 0;
                    if(fd)
                    {
                        error = getData(boundary, in, fd);
                        fclose(fd);
                    }
                    else
                    {
                        printf("WebServerThread::handlePost %d: couldn't open %s for writing\n",
                            __LINE__,
                            tempPath.c_str());
                        error = 1;
                    }

                    if(error)
                    {
                        char string[TEXTLEN];
                        sprintf(string, "Couldn't create the temp file %s", tempPath.c_str());
                        errorReport("555", "WebServerThread::handlePost",
                            string);
                        break;
                    }
                    else
                    {
                        files.insert(std::make_pair(decodedFilename, tempPath));
                        content.insert(std::make_pair(result.name, decodedFilename));
                    }
                }
                else
                {
                    ContentValue result2;
                    getContentValue(&result2, in, boundary);
// must convert all ContentDisposition to UTF-8 to match the names in the filesystem
// TODO: might need a new decoder which keeps &#; intact & just 
// converts invalid codes > 127 to UTF-8.  
                    std::string decodedName;
                    decodeHTML(&decodedName, &result.name);
                    if(!result2.value.compare(CHECKED))
                    {
//printf("WebServerThread::handlePost %d: %s -> %s\n",
//__LINE__, result.name.c_str(), decodedName.c_str());
// for(int i = 0; i < result.name.length(); i++) 
// printf("%02x ", (uint8_t)result.name.at(i));
// printf("\n");
                        fileList.push_back(decodedName);
                    }
                    else
                    {
//                        content.insert(std::make_pair(result.name, result2.value));
                        std::string decodedValue;
                        decodeHTML(&decodedValue, &result2.value);
                        content.insert(std::make_pair(decodedName, decodedValue));
                    }

                    if(result2.eof) break;
                }
            }
            else
                break;
        }

// dump the tables
        printf("WebServerThread::handlePost %d: content\n", __LINE__);
        for(auto i = content.begin(); i != content.end(); i++)
            printf("    key=%s value=%s\n", i->first.c_str(), i->second.c_str());
        printf("WebServerThread::handlePost %d: fileList\n", __LINE__);
        for(auto i = fileList.begin(); i != fileList.end(); i++)
            printf("    %s\n", i->c_str());
        printf("WebServerThread::handlePost %d: files\n", __LINE__);
        for(auto i = files.begin(); i != files.end(); i++)
            printf("    decodedFilename=%s tempPath=%s\n", i->first.c_str(), i->second.c_str());

// perform the operation
        if(content.find("__UPLOAD") != content.end())
            doUpload(path, &files);
        else
        if(content.find("__DELETE") != content.end())
            confirmDelete(path, &fileList);
        else
        if(content.find("__CONFIRMDELETE") != content.end())
            deleteFiles(path, &fileList);
        else
        if(content.find("__MOVE") != content.end())
            confirmMove(path, &fileList, &content.find("__MOVEPATH")->second);
        else
        if(content.find("__CONFIRMMOVE") != content.end())
            moveFiles(path, &fileList, &content.find("__MOVEPATH")->second);
        else
        if(content.find("__RENAME") != content.end())
            confirmRename(path, &fileList);
         else
        if(content.find("__CONFIRMRENAME") != content.end())
            renameFiles(path, &fileList, &content);
        else
        if(content.find("__MKDIR") != content.end())
            doMkdir(path, &content.find("__MKDIRPATH")->second);
        else
        if(content.find("__EDIT") != content.end())
            editFile(path, &fileList, 0);
        else
        if(content.find("__EDITSAVE") != content.end())
            editSave(path, &fileList, &content.find("__EDITTEXT")->second);
        else
        if(content.find("__ABORTDELETE") != content.end() ||
            content.find("__ABORTRENAME") != content.end() ||
            content.find("__ABORTMOVE") != content.end() ||
            content.find("__EDITQUIT") != content.end())
            sendFiles(path);
    }
};

#define TOTAL_THREADS 20
class WebServer
{
public:
    WebServerThread threads[TOTAL_THREADS];

    void run()
    {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in addr;
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;

        int got_port = -1;
        for(int port = Stuff::PORT; port < Stuff::PORT2; port++)
        {
            addr.sin_port = htons(port);

            int result = bind(fd, (struct sockaddr *) &addr, sizeof(addr));
	        if(result)
	        {
		        printf("WebServer:run %d: bind %d failed\n", __LINE__, port);
		        continue;
	        }

            printf("WebServer:run %d: bound port %d\n", __LINE__, port);
            got_port = port;
            break;
        }

        if(got_port < 0)
        {
            printf("WebServer:run %d: couldn't get a port\n", __LINE__);
            return;
        }

// request handler loop
        while(1)
        {
            listen(fd, 256);

		    struct sockaddr_in clientname;
		    socklen_t size = sizeof(clientname);
		    int connection_fd = accept(fd,
                			    (struct sockaddr*)&clientname, 
							    &size);

		    int got_it = 0;
		    for(int i = 0; i < TOTAL_THREADS; i++)
		    {
			    if(!threads[i].busy)
			    {
				    threads[i].startConnection(connection_fd);
				    got_it = 1;
				    break;
			    }
		    }

		    if(!got_it)
		    {
			    printf("WebServer:run %d: out of connections\n", __LINE__);
                close(connection_fd);
		    }
        }
    }
};


int main()
{
    Stuff::initialize();
    WebServer webServer;
    webServer.run();
    return 0;
}







