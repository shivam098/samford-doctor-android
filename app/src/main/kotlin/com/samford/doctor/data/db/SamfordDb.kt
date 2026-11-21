package com.samford.doctor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VisitEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        PageOpEntity::class,
        OutboxEntity::class,
        BlobEntity::class,
        OutboxBlobEntity::class,
        DeliveryEntity::class,
        DemoQueueEntity::class,
        CacheEntity::class,
        EventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SamfordDb : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun inkDao(): InkDao
    abstract fun outboxDao(): OutboxDao
    abstract fun blobDao(): BlobDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun demoDao(): DemoDao
    abstract fun cacheDao(): CacheDao
    abstract fun eventDao(): EventDao

    companion object {
        const val NAME = "samford-doctor.db"

        /** WAL keeps each autosaved stroke durable without blocking reads from the UI. */
        fun open(context: Context, name: String = NAME): SamfordDb =
            Room.databaseBuilder(context.applicationContext, SamfordDb::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        fun inMemory(context: Context): SamfordDb =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SamfordDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
