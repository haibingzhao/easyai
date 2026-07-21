package com.easy.easyai.common.core.model


interface HasInfoString {

    /**
     * Human-readable String representation of the object
     * @param verbose if true, include more detailed information
     * @param indent the number of tabs to indent the string
     */
    fun infoString(
      verbose: Boolean? = false,
      indent: Int = 0,
    ): String
}
