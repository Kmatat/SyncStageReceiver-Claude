package com.example.syncstagereceiver.network

object CommandConstants {
    // Actions
    const val ACTION = "action"
    const val CMD_PLAY = "PLAY"
    const val CMD_PAUSE = "PAUSE"
    const val CMD_STOP = "STOP"
    const val CMD_SEEK = "SEEK"
    const val CMD_PLAY_VIDEO_URL = "PLAY_VIDEO_URL"
    const val CMD_SYNC_PLAYLIST = "SYNC_PLAYLIST"
    const val CMD_REQUEST_STATUS = "REQUEST_STATUS"
    const val CMD_HEARTBEAT = "HEARTBEAT"
    const val CMD_SET_NAME = "SET_NAME"
    const val CMD_SET_MASTER_TIME = "SET_MASTER_TIME"

    // Keys
    const val KEY_URL = "url"
    const val KEY_MIME_TYPE = "mimeType"
    const val KEY_POSITION = "position"
    const val KEY_PLAYLIST = "playlist"
    const val KEY_INDEX = "index"
    const val KEY_START_TIME = "startTime"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_FILENAME = "filename"
}