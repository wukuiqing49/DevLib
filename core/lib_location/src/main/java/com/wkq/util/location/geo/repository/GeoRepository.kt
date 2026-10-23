package com.wkq.util.location.geo.repository

import android.content.Context
import androidx.room.Room
import com.wkq.util.location.geo.GeoDatabase
import com.wkq.util.location.geo.OfflineGeoCoder
import com.wkq.util.location.geo.OfflineGeoCoderImpl
import com.wkq.util.location.LocationLog

object GeoRepository {
    private const val TAG = "GeoRepository"
    private const val DB_NAME = "geonames.db"

    @Volatile
    private var database: GeoDatabase? = null

    @Volatile
    private var geoCoder: OfflineGeoCoder? = null

    fun init(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) {
                    LocationLog.d(TAG, "Initializing GeoNames database from asset...")
                    val createdDatabase = Room.databaseBuilder(
                        context.applicationContext,
                        GeoDatabase::class.java,
                        DB_NAME
                    ).createFromAsset(DB_NAME)
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
                    database = createdDatabase
                    geoCoder = OfflineGeoCoderImpl(
                        createdDatabase.geoDao(),
                        context.applicationContext
                    )
                    LocationLog.d(TAG, "GeoNames database initialization triggered successfully.")
                }
            }
        }
    }

    fun getGeoCoder(context: Context): OfflineGeoCoder {
        geoCoder?.let { return it }
        return synchronized(this) {
            geoCoder ?: run {
                init(context)
                val currentDatabase = database
                    ?: error("GeoNames database initialization failed")
                OfflineGeoCoderImpl(
                    currentDatabase.geoDao(),
                    context.applicationContext
                ).also { geoCoder = it }
            }
        }
    }

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
            geoCoder = null
        }
    }
}
