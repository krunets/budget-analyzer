package ru.wtrn.budgetanalyzer.service

import org.springframework.stereotype.Service
import ru.wtrn.budgetanalyzer.configuration.properties.LimitsProperties
import ru.wtrn.budgetanalyzer.entity.CurrentLimitEntity
import ru.wtrn.budgetanalyzer.model.Amount
import ru.wtrn.budgetanalyzer.model.CalculatedDayLimit
import ru.wtrn.budgetanalyzer.repository.CurrentLimitRepository
import ru.wtrn.budgetanalyzer.util.atEndOfMonth
import ru.wtrn.budgetanalyzer.util.remainingAmount
import ru.wtrn.budgetanalyzer.util.spentAmount
import java.math.BigDecimal

@Service
class LimitsService(
    private val currentLimitRepository: CurrentLimitRepository,
    private val limitsProperties: LimitsProperties
) {
    suspend fun decreaseLimit(amount: Amount): ResultingLimits {
        val foundLimits = currentLimitRepository.findActiveLimits(
            tag = LIMIT_TAG,
            currency = amount.currency
        )
            .associateBy { it.timespan }

        val monthLimit = (foundLimits[CurrentLimitEntity.LimitTimespan.MONTH] ?: constructMonthLimit())
        val dayLimit = foundLimits[CurrentLimitEntity.LimitTimespan.DAY] ?: constructDayLimit(monthLimit)

        currentLimitRepository.increaseSpentAmount(
            limitIds = listOf(monthLimit.id, dayLimit.id),
            amountValue = amount.value
        )

        listOf(dayLimit, monthLimit).forEach {
            it.spentValue += amount.value
        }

        val nextDay = dayLimit.periodStart.plusDays(1)
        val nextDayCalculatedLimit = when(nextDay) {
            monthLimit.periodStart.atEndOfMonth() -> CalculatedDayLimit.of(
                monthStart = nextDay,
                date = nextDay,
                spentValue = BigDecimal.ZERO,
                limitValue = monthLimit.limitValue,
                currency = monthLimit.currency
            )
            else -> CalculatedDayLimit.of(
                monthStart = monthLimit.periodStart,
                date = nextDay,
                spentValue = monthLimit.spentValue,
                limitValue = monthLimit.limitValue,
                currency = monthLimit.currency
            )
        }

        return ResultingLimits(
            todayLimit = dayLimit,
            monthLimit = monthLimit,
            nextDayCalculatedLimit = nextDayCalculatedLimit
        )
    }

    private suspend fun constructMonthLimit(): CurrentLimitEntity {
        val entity = CurrentLimitEntity.constructMonthLimit(
            tag = LIMIT_TAG,
            limitAmount = Amount(
                value = limitsProperties.daily,
                currency = limitsProperties.currency
            ),
            timezone = limitsProperties.timezone
        )
        currentLimitRepository.insert(entity)
        return entity
    }

    private suspend fun constructDayLimit(monthLimit: CurrentLimitEntity): CurrentLimitEntity {
        val entity = CurrentLimitEntity.constructDayLimit(
            monthLimit = monthLimit
        )
        currentLimitRepository.insert(entity)
        return entity
    }

    companion object {
        private const val LIMIT_TAG = "Daily"
    }

    data class ResultingLimits(
        val todayLimit: CurrentLimitEntity,
        val monthLimit: CurrentLimitEntity,
        val nextDayCalculatedLimit: CalculatedDayLimit
    )
}
