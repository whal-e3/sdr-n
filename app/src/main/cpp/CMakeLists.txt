cmake_minimum_required(VERSION 3.22.1)

project(satellite_rx LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(satellite_rx_core STATIC receiver_core.cpp nfm_audio.cpp
                                     decoders/ax25_afsk1200.cpp)
set_target_properties(satellite_rx_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(satellite_rx_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(ANDROID)
    # Pinned, vendored libusb 1.0.30 and RTL-SDR Blog sources. USB device
    # discovery is deliberately bypassed in favor of Android's granted fd.
    set(LIBUSB_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LIBUSB_INSTALL_TARGETS OFF CACHE BOOL "" FORCE)
    set(LIBUSB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(LIBUSB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/libusb-cmake)

    set(RTLSDR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rtl-sdr)
    add_library(rtlsdr_vendor STATIC
        ${RTLSDR_DIR}/src/librtlsdr.c
        ${RTLSDR_DIR}/src/tuner_e4k.c
        ${RTLSDR_DIR}/src/tuner_fc0012.c
        ${RTLSDR_DIR}/src/tuner_fc0013.c
        ${RTLSDR_DIR}/src/tuner_fc2580.c
        ${RTLSDR_DIR}/src/tuner_r82xx.c)
    set_target_properties(rtlsdr_vendor PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_definitions(rtlsdr_vendor PRIVATE rtlsdr_STATIC)
    target_include_directories(rtlsdr_vendor PUBLIC ${RTLSDR_DIR}/include)
    target_include_directories(rtlsdr_vendor PRIVATE ${RTLSDR_DIR}/src)
    target_link_libraries(rtlsdr_vendor PUBLIC libusb::usb-1.0)

    set(HACKRF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libhackrf)
    add_library(hackrf_vendor STATIC ${HACKRF_DIR}/hackrf.c)
    set_target_properties(hackrf_vendor PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_options(hackrf_vendor PRIVATE -ffunction-sections -fdata-sections
                                                 -fvisibility=hidden)
    target_include_directories(hackrf_vendor PUBLIC ${HACKRF_DIR})
    target_link_libraries(hackrf_vendor PUBLIC libusb::usb-1.0)

    add_library(satellite_rx SHARED jni_bridge.cpp rtl_sdr_driver.cpp
                                    hackrf_driver.cpp)
    target_compile_definitions(satellite_rx PRIVATE rtlsdr_STATIC)
    target_link_options(satellite_rx PRIVATE -Wl,--gc-sections
                                             -Wl,--exclude-libs,ALL
                                             -Wl,-z,max-page-size=16384
                                             -Wl,-z,common-page-size=16384)
    target_link_libraries(satellite_rx PRIVATE satellite_rx_core rtlsdr_vendor
                                               hackrf_vendor log)
    add_custom_command(TARGET satellite_rx POST_BUILD
        COMMAND ${CMAKE_COMMAND}
            -DNM_TOOL=${CMAKE_NM}
            -DRX_BINARY=$<TARGET_FILE:satellite_rx>
            -P ${CMAKE_CURRENT_SOURCE_DIR}/check_rx_only.cmake
        VERBATIM)
else()
    enable_testing()
    add_executable(receiver_core_test tests/receiver_core_test.cpp)
    target_link_libraries(receiver_core_test PRIVATE satellite_rx_core)
    add_test(NAME receiver_core_test COMMAND receiver_core_test)
    add_executable(ax25_afsk1200_test tests/ax25_afsk1200_test.cpp)
    target_link_libraries(ax25_afsk1200_test PRIVATE satellite_rx_core)
    add_test(NAME ax25_afsk1200_test COMMAND ax25_afsk1200_test)
    add_executable(ax25_receiver_integration_test
                   tests/ax25_receiver_integration_test.cpp)
    target_link_libraries(ax25_receiver_integration_test PRIVATE satellite_rx_core)
    add_test(NAME ax25_receiver_integration_test
             COMMAND ax25_receiver_integration_test)
endif()
