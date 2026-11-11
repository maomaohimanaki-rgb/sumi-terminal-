cmake_minimum_required(VERSION 3.22.1)
project(pty_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pty_core SHARED pty_core.cpp)
target_compile_options(pty_core PRIVATE 
    -Wall -Wextra -O3 -fomit-frame-pointer -fvisibility=hidden
)
target_link_libraries(pty_core log)
