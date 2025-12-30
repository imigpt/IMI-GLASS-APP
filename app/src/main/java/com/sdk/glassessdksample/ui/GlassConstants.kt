package com.sdk.glassessdksample.ui

object GlassConstants {

    // NOTE:
    // Abhi ke liye sab same UUID rakhe gaye hain, kyunki GLASSES SDK ek hi
    // notify characteristic (de5bf7...) se data bhej raha hai.
    // Event ka difference "loadData" ke bytes se nikalna padega,
    // UUID se nahi.

    // Make sure NO trailing spaces in any UUID string
    const val CAMERA_OPENED   = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val PHOTO_TAKEN     = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val VIDEO_START     = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val VIDEO_STOP      = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val BATTERY_LEVEL   = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val BUTTON_SINGLE   = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val BUTTON_DOUBLE   = "de5bf729-d711-4e47-af26-65e3012a5dc7"
    const val BUTTON_LONG     = "de5bf729-d711-4e47-af26-65e3012a5dc7"
}
