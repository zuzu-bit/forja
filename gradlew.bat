package com.forja.app.core.data

import android.content.Context
import androidx.room.Room
import com.forja.app.core.data.db.ForjaDatabase
import com.forja.app.core.data.db.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ForjaDatabase =
        Room.databaseBuilder(context, ForjaDatabase::class.java, "forja.db").build()

    @Provides
    fun provideUserDao(db: ForjaDatabase): UserDao = db.userDao()
}
