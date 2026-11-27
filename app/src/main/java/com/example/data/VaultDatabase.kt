package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class VaultTypeConverters {
    @TypeConverter
    fun fromMediaCategory(category: MediaCategory): String = category.name

    @TypeConverter
    fun toMediaCategory(value: String): MediaCategory =
        runCatching { MediaCategory.valueOf(value) }.getOrDefault(MediaCategory.OTHER)
}

@Database(
    entities = [AlbumEntity::class, VaultFileEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(VaultTypeConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_files ADD COLUMN contentHash TEXT")
            }
        }

        @Volatile
        private var instance: VaultDatabase? = null

        fun getInstance(context: Context): VaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "brankasku_vault.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
