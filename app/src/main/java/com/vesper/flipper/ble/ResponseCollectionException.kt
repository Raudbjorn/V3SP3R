package com.vesper.flipper.ble

/** Internal collector failure; public commands translate it to ProtocolResponse.Error. */
internal class ResponseCollectionException(message: String) : IllegalStateException(message)
