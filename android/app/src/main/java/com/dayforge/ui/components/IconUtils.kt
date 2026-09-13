package com.dayforge.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Returns the icon ImageVector for a given icon resource ID (1-91).
 * Must match IconPicker.kt and IconMapper.kt mappings exactly.
 *
 * Icons 1-8 are legacy icons preserved for backward compatibility.
 * Icons 9-52 are category-based icons added in Phase 67.
 * Icon 53 is the TaskAlt icon added in Phase 88 for temporary tasks.
 * Icons 54-91 are new category icons: Personal Hygiene, Social, Finance,
 * Creative, Transportation, Entertainment, Household, Outdoor, Technology.
 */
fun getIconForResId(iconResId: Int): ImageVector {
    return when (iconResId) {
        // Legacy icons 1-8
        1 -> Icons.Rounded.WaterDrop
        2 -> Icons.AutoMirrored.Rounded.DirectionsRun
        3 -> Icons.Rounded.Bedtime
        4 -> Icons.Rounded.LunchDining
        5 -> Icons.Rounded.AutoStories
        6 -> Icons.Rounded.SelfImprovement
        7 -> Icons.Rounded.Work
        8 -> Icons.Filled.Favorite
        // Fitness icons (9-17)
        9 -> Icons.Rounded.FitnessCenter
        10 -> Icons.AutoMirrored.Rounded.DirectionsBike
        11 -> Icons.Rounded.SportsGymnastics
        12 -> Icons.Rounded.Sports
        13 -> Icons.Rounded.Pool
        14 -> Icons.Rounded.Hiking
        15 -> Icons.AutoMirrored.Rounded.DirectionsWalk
        16 -> Icons.Rounded.SportsSoccer
        17 -> Icons.Rounded.SportsBasketball
        // Health icons (18-25)
        18 -> Icons.Rounded.LocalHospital
        19 -> Icons.Rounded.MedicalServices
        20 -> Icons.Rounded.Healing
        21 -> Icons.Rounded.Bloodtype
        22 -> Icons.Rounded.Sanitizer
        23 -> Icons.Rounded.Restaurant
        24 -> Icons.Rounded.LocalPharmacy
        25 -> Icons.Rounded.Vaccines
        // Learning icons (26-34)
        26 -> Icons.Rounded.School
        27 -> Icons.AutoMirrored.Rounded.MenuBook
        28 -> Icons.Rounded.Lightbulb
        29 -> Icons.Rounded.Calculate
        30 -> Icons.Rounded.Translate
        31 -> Icons.Rounded.Science
        32 -> Icons.Rounded.EditNote
        33 -> Icons.Rounded.Psychology
        34 -> Icons.Rounded.Code
        // Mental icons (35-43)
        35 -> Icons.Rounded.Spa
        36 -> Icons.Rounded.SentimentSatisfied
        37 -> Icons.Rounded.Mood
        38 -> Icons.Rounded.PsychologyAlt
        39 -> Icons.Rounded.SentimentVerySatisfied
        40 -> Icons.Rounded.Nature
        41 -> Icons.Rounded.Forest
        42 -> Icons.Rounded.Grain
        43 -> Icons.Rounded.EmojiEvents
        // Lifestyle icons (44-52)
        44 -> Icons.Rounded.Home
        45 -> Icons.Rounded.ShoppingBag
        46 -> Icons.Rounded.ShoppingCart
        47 -> Icons.Rounded.CleaningServices
        48 -> Icons.Rounded.LocalLaundryService
        49 -> Icons.Rounded.Pets
        50 -> Icons.Rounded.FamilyRestroom
        51 -> Icons.Rounded.Celebration
        52 -> Icons.Rounded.Nightlife
        // TODO icons (53)
        53 -> Icons.Rounded.TaskAlt
        // Personal Hygiene icons (54-61)
        54 -> Icons.Rounded.Shower
        55 -> Icons.Rounded.Bathtub
        56 -> Icons.Rounded.Wash
        57 -> Icons.Rounded.Soap
        58 -> Icons.Rounded.Face
        59 -> Icons.Rounded.ContentCut
        60 -> Icons.Rounded.CleanHands
        61 -> Icons.Rounded.PanTool
        // Social icons (62-66)
        62 -> Icons.Rounded.People
        63 -> Icons.Rounded.Group
        64 -> Icons.AutoMirrored.Rounded.Chat
        65 -> Icons.Rounded.Forum
        66 -> Icons.Rounded.Handshake
        // Finance icons (67-71)
        67 -> Icons.Rounded.AccountBalance
        68 -> Icons.Rounded.Savings
        69 -> Icons.Rounded.Payment
        70 -> Icons.Rounded.Receipt
        71 -> Icons.Rounded.AttachMoney
        // Creative icons (72-76)
        72 -> Icons.Rounded.Brush
        73 -> Icons.Rounded.MusicNote
        74 -> Icons.Rounded.CameraAlt
        75 -> Icons.Rounded.Edit
        76 -> Icons.Rounded.DesignServices
        // Transportation icons (77-79)
        77 -> Icons.Rounded.DirectionsCar
        78 -> Icons.Rounded.Train
        79 -> Icons.Rounded.Flight
        // Entertainment icons (80-82)
        80 -> Icons.Rounded.Movie
        81 -> Icons.Rounded.Tv
        82 -> Icons.Rounded.SportsEsports
        // Household icons (83-85)
        83 -> Icons.Rounded.Kitchen
        84 -> Icons.Rounded.Build
        85 -> Icons.Rounded.Iron
        // Outdoor icons (86-88)
        86 -> Icons.Rounded.Park
        87 -> Icons.Rounded.Landscape
        88 -> Icons.Rounded.Terrain
        // Technology icons (89-91)
        89 -> Icons.Rounded.Phone
        90 -> Icons.Rounded.Computer
        91 -> Icons.Rounded.Devices
        // Fallback for unknown IDs
        else -> Icons.Filled.Favorite // Default to health icon
    }
}
