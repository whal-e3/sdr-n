# Catch accidental retention of libhackrf's transmit entry points in the APK.
if(NOT EXISTS "${RX_BINARY}" OR NOT EXISTS "${NM_TOOL}")
    message(FATAL_ERROR "Cannot verify receive-only native binary")
endif()

execute_process(
    COMMAND "${NM_TOOL}" --defined-only "${RX_BINARY}"
    RESULT_VARIABLE nm_status
    OUTPUT_VARIABLE symbols
    ERROR_VARIABLE nm_error)
if(NOT nm_status EQUAL 0)
    message(FATAL_ERROR "Symbol inspection failed: ${nm_error}")
endif()

if(symbols MATCHES "hackrf_(start_tx|stop_tx|set_txvga_gain|set_tx_underrun_limit|set_tx_block_complete_callback)")
    message(FATAL_ERROR "Transmit function retained in satellite_rx")
endif()
