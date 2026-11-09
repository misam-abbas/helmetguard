package com.misamabbas.helmetguard.di

import android.content.Context
import androidx.room.Room
import com.misamabbas.helmetguard.BuildConfig
import com.misamabbas.helmetguard.auth.EncryptedSessionStore
import com.misamabbas.helmetguard.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "helmetguard.db").build()

    /** Null until the Supabase values are added to local.properties. */
    @Provides
    @Singleton
    fun supabase(sessions: EncryptedSessionStore): SupabaseClient? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) return null
        return createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            httpEngine = OkHttp.create()
            install(Auth) {
                sessionManager = sessions
            }
            install(Postgrest)
            install(Storage)
        }
    }
}
