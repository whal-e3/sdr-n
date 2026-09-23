cmake_minimum_required(VERSION 3.16)
project(lrpt_cadu_experiment LANGUAGES CXX)

add_library(lrpt_cadu STATIC cadu_sync.cpp)
target_compile_features(lrpt_cadu PUBLIC cxx_std_17)
target_include_directories(lrpt_cadu PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(MSVC)
    target_compile_options(lrpt_cadu PRIVATE /W4 /WX)
else()
    target_compile_options(lrpt_cadu PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

enable_testing()
add_executable(lrpt_cadu_tests tests.cpp)
target_link_libraries(lrpt_cadu_tests PRIVATE lrpt_cadu)
add_test(NAME lrpt_cadu_tests COMMAND lrpt_cadu_tests)
