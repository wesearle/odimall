cmake_minimum_required(VERSION 3.16)
project(odimall_vm_alpha LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
set(HTTPLIB_USE_OPENSSL_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
set(HTTPLIB_USE_ZLIB_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
set(HTTPLIB_USE_BROTLI_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
set(HTTPLIB_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  cpphttplib
  GIT_REPOSITORY https://github.com/yhirose/cpp-httplib.git
  GIT_TAG v0.16.3
)
FetchContent_MakeAvailable(cpphttplib)

add_executable(vm_alpha main.cpp)
target_link_libraries(vm_alpha PRIVATE httplib::httplib)
if(NOT MSVC)
  target_link_libraries(vm_alpha PRIVATE pthread)
endif()
