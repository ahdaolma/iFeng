cmake_minimum_required(VERSION 3.22.1)

project(native-lib)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(llama)

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)

target_include_directories(native-lib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/llama)

target_link_libraries(
        native-lib
        llama
        ${log-lib}
)
