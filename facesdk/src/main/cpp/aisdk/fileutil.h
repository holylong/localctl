#ifndef _FILE_UTIL_H_
#define _FILE_UTIL_H_

#include <unistd.h>
#include <string>

class FileUtil{
public:
    static bool isFileExist(const std::string& filePath)
    {
        return access(filePath.c_str(), F_OK) == 0;
    }
};

#endif