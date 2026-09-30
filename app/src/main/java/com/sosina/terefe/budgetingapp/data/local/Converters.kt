package com.sosina.terefe.budgetingapp.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Translates types the database doesn't understand into ones it does.
 *
 * Dates are stored as numbers so the database can sort them and
 * search by range quickly ("all expenses between Sep 25 and Oct 24").
 */
class Converters {

    // LocalDate <-> number of days since 1970-01-01
    @TypeConverter
    fun localDateToLong(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun longToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    // LocalDateTime <-> number of seconds since 1970-01-01.
    // UTC is used only as a fixed reference so the stored time never shifts;
    // the time itself is always the local time the user entered.
    @TypeConverter
    fun localDateTimeToLong(dateTime: LocalDateTime?): Long? =
        dateTime?.toEpochSecond(ZoneOffset.UTC)

    @TypeConverter
    fun longToLocalDateTime(value: Long?): LocalDateTime? =
        value?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
}
