package com.nkudrin713.kradnik.telegram.localization

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(name = "telegram_user_preferences")
class TelegramUserPreference(
    @Id
    @Column(name = "telegram_user_id")
    var telegramUserId: Long = 0,

    @Convert(converter = BotLanguageConverter::class)
    @Column(nullable = false)
    var language: BotLanguage = BotLanguage.EN,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
)

interface TelegramUserPreferenceRepository : JpaRepository<TelegramUserPreference, Long>

@Service
class TelegramUserPreferenceService(
    private val repository: TelegramUserPreferenceRepository,
) {
    @Transactional(readOnly = true)
    fun selectedLanguage(telegramUserId: Long): BotLanguage? {
        return repository.findById(telegramUserId).orElse(null)?.language
    }

    @Transactional(readOnly = true)
    fun resolveLanguage(telegramUserId: Long): BotLanguage {
        return selectedLanguage(telegramUserId)
            ?: BotLanguage.EN
    }

    @Transactional
    fun selectLanguage(telegramUserId: Long, language: BotLanguage) {
        val preference = repository.findById(telegramUserId).orElseGet {
            TelegramUserPreference(telegramUserId = telegramUserId)
        }
        preference.language = language
        repository.save(preference)
    }
}
