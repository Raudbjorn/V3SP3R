package com.vesper.flipper.domain.executor
import com.vesper.flipper.domain.model.*
object TypedDeviceCommands {
    fun render(action: CommandAction, args: CommandArgs): String {
        fun pin(): String = requireNotNull(args.pin).also { require(it.matches(Regex("P[A-H][0-9]{1,2}"))) { "Invalid GPIO pin" } }
        fun bit(value: Int?): Int = requireNotNull(value).also { require(it in 0..1) }
        return when (action) {
            CommandAction.GPIO_READ -> "gpio read ${pin()}"
            CommandAction.GPIO_SET -> "gpio set ${pin()} ${bit(args.value)}"
            CommandAction.GPIO_MODE -> "gpio mode ${pin()} ${bit(args.mode)}"
            CommandAction.APPS_LIST -> "loader list"
            CommandAction.GET_SYSTEM_INFO -> "device_info"
            CommandAction.NFC_DETECT -> "nfc detect"
            CommandAction.RFID_READ -> "rfid read"
            CommandAction.IR_RECEIVE -> "ir rx"
            CommandAction.RFID_WRITE -> {
                val type = requireNotNull(args.keyType).also { require(it.matches(Regex("[A-Za-z0-9_-]{1,32}"))) }
                val data = requireNotNull(args.keyData).also { require(it.matches(Regex("(?:[0-9a-fA-F]{2}){1,32}"))) }
                "rfid write $type $data"
            }
            else -> error("Not a supported typed CLI action")
        }
    }
}
