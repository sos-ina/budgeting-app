package com.sosina.terefe.budgetingapp.domain.mascot

import com.sosina.terefe.budgetingapp.domain.model.MascotType

enum class MascotMood { HAPPY, WORRIED, SWEATING, FAINTED }

object Mascot {

    /**
     * The mood from this month's money:
     * over 50% left = happy, 20-50% = worried, under 20% = sweating, overspent = fainted.
     */
    fun moodFor(income: Long, spent: Long): MascotMood {
        if (income <= 0L) return if (spent > 0L) MascotMood.FAINTED else MascotMood.HAPPY
        val leftFraction = (income - spent).toDouble() / income
        return when {
            leftFraction < 0.0 -> MascotMood.FAINTED
            leftFraction < 0.20 -> MascotMood.SWEATING
            leftFraction <= 0.50 -> MascotMood.WORRIED
            else -> MascotMood.HAPPY
        }
    }

    fun emoji(type: MascotType): String = when (type) {
        MascotType.PIGEON -> "🐦"
        MascotType.HORSE -> "🐴"
        MascotType.DONKEY -> "🫏"
        MascotType.CAT -> "🐱"
        MascotType.DOG -> "🐶"
        MascotType.NONE -> ""
    }

    fun name(type: MascotType): String = when (type) {
        MascotType.PIGEON -> "Pigeon"
        MascotType.HORSE -> "Horse"
        MascotType.DONKEY -> "Donkey"
        MascotType.CAT -> "Cat"
        MascotType.DOG -> "Dog"
        MascotType.NONE -> ""
    }

    /** Short description of the mood, for screen readers. */
    fun moodDescription(mood: MascotMood): String = when (mood) {
        MascotMood.HAPPY -> "happy, plenty of money left"
        MascotMood.WORRIED -> "worried, about half the money is spent"
        MascotMood.SWEATING -> "sweating, less than a fifth of the money is left"
        MascotMood.FAINTED -> "fainted, this month is overspent"
    }

    /** What the mascot says. Each animal has its own personality. */
    fun lines(type: MascotType, mood: MascotMood): List<String> = when (type) {
        MascotType.PIGEON -> when (mood) {
            MascotMood.HAPPY -> listOf(
                "Coo! We're rolling in crumbs this month.",
                "Plenty left. I might even upgrade to a fancy bench."
            )
            MascotMood.WORRIED -> listOf(
                "Half the crumbs are gone. I'm keeping an eye on you.",
                "Coo... maybe skip the fancy stuff for a bit?"
            )
            MascotMood.SWEATING -> listOf(
                "We're down to the last crumbs. I've started eyeing strangers' sandwiches.",
                "COO. COO. This is not a drill."
            )
            MascotMood.FAINTED -> listOf(
                "*lies flat on the pavement* ...overspent.",
                "I've seen things. Terrible things. Your spending, mostly."
            )
        }

        MascotType.HORSE -> when (mood) {
            MascotMood.HAPPY -> listOf(
                "Neigh-ce! We're galloping through this month in style!",
                "So much money left. I feel majestic."
            )
            MascotMood.WORRIED -> listOf(
                "Hold your horses. Well, hold ME. We're halfway through the hay.",
                "I'm not panicking. I'm just... trotting nervously."
            )
            MascotMood.SWEATING -> listOf(
                "The hay is almost gone and I'm FAR too dramatic for this.",
                "Why do you keep spending? Is it something I said?!"
            )
            MascotMood.FAINTED -> listOf(
                "*collapses dramatically* Tell my stable I loved them.",
                "Overspent. I need to lie down. Forever, possibly."
            )
        }

        MascotType.DONKEY -> when (mood) {
            MascotMood.HAPPY -> listOf(
                "Hee-haw. Money's fine. Don't get used to it.",
                "Budget's good. I refuse to be excited about it."
            )
            MascotMood.WORRIED -> listOf(
                "Half gone already? I'm not carrying any more of your purchases.",
                "I've dug in my hooves. No more snacks."
            )
            MascotMood.SWEATING -> listOf(
                "I told you so. I'll keep telling you so.",
                "Almost broke. I'm too stubborn to faint, but only just."
            )
            MascotMood.FAINTED -> listOf(
                "Fine. FINE. I fainted. Happy now?",
                "*flops over* ...hee... haw..."
            )
        }

        MascotType.CAT -> when (mood) {
            MascotMood.HAPPY -> listOf(
                "Acceptable. You may continue to feed me.",
                "Plenty left. Buy me something shiny to knock off a shelf."
            )
            MascotMood.WORRIED -> listOf(
                "I'm not judging you. *judges you*",
                "Half the money is gone and my food bowl is suspiciously quiet."
            )
            MascotMood.SWEATING -> listOf(
                "Cats don't sweat. And yet, here we are.",
                "If the treats stop, I'm moving in with the neighbours."
            )
            MascotMood.FAINTED -> listOf(
                "I've used one of my nine lives on this budget.",
                "*dramatically falls off the table* Overspent."
            )
        }

        MascotType.DOG -> when (mood) {
            MascotMood.HAPPY -> listOf(
                "WOW we have SO MUCH money! Good human! Best human!",
                "Everything's great! Can we go for a walk? Walks are free!"
            )
            MascotMood.WORRIED -> listOf(
                "Half gone? That's okay! I still love you! Maybe fewer treats though?",
                "*tilts head* Are we... spending a lot?"
            )
            MascotMood.SWEATING -> listOf(
                "I'm not worried! I'm just panting for no reason! Totally normal!",
                "I buried a bone for emergencies. Is this an emergency?"
            )
            MascotMood.FAINTED -> listOf(
                "*plays dead* ...this isn't a trick. We're overspent.",
                "I still love you. The budget doesn't, though."
            )
        }

        MascotType.NONE -> emptyList()
    }
}
