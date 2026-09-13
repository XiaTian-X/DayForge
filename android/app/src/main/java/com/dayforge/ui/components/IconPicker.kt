package com.dayforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R

data class IconOption(
    val name: String,
    val icon: ImageVector,
    val id: Int
)

enum class IconCategory(val stringResId: Int) {
    FITNESS(R.string.icon_category_fitness),
    HEALTH(R.string.icon_category_health),
    LEARNING(R.string.icon_category_learning),
    MENTAL(R.string.icon_category_mental),
    LIFESTYLE(R.string.icon_category_lifestyle),
    TODO(R.string.icon_category_todo),
    PERSONAL_HYGIENE(R.string.icon_category_personal_hygiene),
    SOCIAL(R.string.icon_category_social),
    FINANCE(R.string.icon_category_finance),
    CREATIVE(R.string.icon_category_creative),
    TRANSPORTATION(R.string.icon_category_transportation),
    ENTERTAINMENT(R.string.icon_category_entertainment),
    HOUSEHOLD(R.string.icon_category_household),
    OUTDOOR(R.string.icon_category_outdoor),
    TECHNOLOGY(R.string.icon_category_technology)
}

// Fitness icons (IDs 1-2, 9-17)
val FITNESS_ICONS = listOf(
    IconOption("exercise", Icons.AutoMirrored.Rounded.DirectionsRun, 2),
    IconOption("fitness_center", Icons.Rounded.FitnessCenter, 9),
    IconOption("directions_bike", Icons.AutoMirrored.Rounded.DirectionsBike, 10),
    IconOption("sports_gymnastics", Icons.Rounded.SportsGymnastics, 11),
    IconOption("sports", Icons.Rounded.Sports, 12),
    IconOption("pool", Icons.Rounded.Pool, 13),
    IconOption("hiking", Icons.Rounded.Hiking, 14),
    IconOption("directions_walk", Icons.AutoMirrored.Rounded.DirectionsWalk, 15),
    IconOption("sports_soccer", Icons.Rounded.SportsSoccer, 16),
    IconOption("sports_basketball", Icons.Rounded.SportsBasketball, 17)
)

// Health icons (IDs 1, 3-4, 8, 18-25)
val HEALTH_ICONS = listOf(
    IconOption("water", Icons.Rounded.WaterDrop, 1),
    IconOption("sleep", Icons.Rounded.Bedtime, 3),
    IconOption("food", Icons.Rounded.LunchDining, 4),
    IconOption("health", Icons.Filled.Favorite, 8),
    IconOption("local_hospital", Icons.Rounded.LocalHospital, 18),
    IconOption("medical_services", Icons.Rounded.MedicalServices, 19),
    IconOption("healing", Icons.Rounded.Healing, 20),
    IconOption("bloodtype", Icons.Rounded.Bloodtype, 21),
    IconOption("sanitizer", Icons.Rounded.Sanitizer, 22),
    IconOption("restaurant", Icons.Rounded.Restaurant, 23),
    IconOption("local_pharmacy", Icons.Rounded.LocalPharmacy, 24),
    IconOption("vaccines", Icons.Rounded.Vaccines, 25)
)

// Learning icons (IDs 5, 26-34)
val LEARNING_ICONS = listOf(
    IconOption("book", Icons.Rounded.AutoStories, 5),
    IconOption("school", Icons.Rounded.School, 26),
    IconOption("menu_book", Icons.AutoMirrored.Rounded.MenuBook, 27),
    IconOption("lightbulb", Icons.Rounded.Lightbulb, 28),
    IconOption("calculate", Icons.Rounded.Calculate, 29),
    IconOption("translate", Icons.Rounded.Translate, 30),
    IconOption("science", Icons.Rounded.Science, 31),
    IconOption("edit_note", Icons.Rounded.EditNote, 32),
    IconOption("psychology", Icons.Rounded.Psychology, 33),
    IconOption("code", Icons.Rounded.Code, 34)
)

// Mental icons (IDs 6, 35-43)
val MENTAL_ICONS = listOf(
    IconOption("meditation", Icons.Rounded.SelfImprovement, 6),
    IconOption("spa", Icons.Rounded.Spa, 35),
    IconOption("sentiment_satisfied", Icons.Rounded.SentimentSatisfied, 36),
    IconOption("mood", Icons.Rounded.Mood, 37),
    IconOption("psychology_alt", Icons.Rounded.PsychologyAlt, 38),
    IconOption("sentiment_very_satisfied", Icons.Rounded.SentimentVerySatisfied, 39),
    IconOption("nature", Icons.Rounded.Nature, 40),
    IconOption("forest", Icons.Rounded.Forest, 41),
    IconOption("grain", Icons.Rounded.Grain, 42),
    IconOption("self_improvement", Icons.Rounded.EmojiEvents, 43)
)

// Lifestyle icons (IDs 7, 44-52)
val LIFESTYLE_ICONS = listOf(
    IconOption("work", Icons.Rounded.Work, 7),
    IconOption("home", Icons.Rounded.Home, 44),
    IconOption("shopping_bag", Icons.Rounded.ShoppingBag, 45),
    IconOption("shopping_cart", Icons.Rounded.ShoppingCart, 46),
    IconOption("cleaning_services", Icons.Rounded.CleaningServices, 47),
    IconOption("local_laundry_service", Icons.Rounded.LocalLaundryService, 48),
    IconOption("pets", Icons.Rounded.Pets, 49),
    IconOption("family_restroom", Icons.Rounded.FamilyRestroom, 50),
    IconOption("celebration", Icons.Rounded.Celebration, 51),
    IconOption("nightlife", Icons.Rounded.Nightlife, 52)
)

// TODO icons (ID 53)
val TODO_ICONS = listOf(
    IconOption("task_alt", Icons.Rounded.TaskAlt, 53)
)

// Personal Hygiene icons (IDs 54-61)
val PERSONAL_HYGIENE_ICONS = listOf(
    IconOption("shower", Icons.Rounded.Shower, 54),
    IconOption("bathtub", Icons.Rounded.Bathtub, 55),
    IconOption("wash", Icons.Rounded.Wash, 56),
    IconOption("brush_teeth", Icons.Rounded.Soap, 57),
    IconOption("face_wash", Icons.Rounded.Face, 58),
    IconOption("hair_brush", Icons.Rounded.ContentCut, 59),
    IconOption("shave", Icons.Rounded.CleanHands, 60),
    IconOption("nail_care", Icons.Rounded.PanTool, 61)
)

// Social icons (IDs 62-66)
val SOCIAL_ICONS = listOf(
    IconOption("people", Icons.Rounded.People, 62),
    IconOption("group", Icons.Rounded.Group, 63),
    IconOption("chat", Icons.AutoMirrored.Rounded.Chat, 64),
    IconOption("forum", Icons.Rounded.Forum, 65),
    IconOption("handshake", Icons.Rounded.Handshake, 66)
)

// Finance icons (IDs 67-71)
val FINANCE_ICONS = listOf(
    IconOption("account_balance", Icons.Rounded.AccountBalance, 67),
    IconOption("savings", Icons.Rounded.Savings, 68),
    IconOption("payment", Icons.Rounded.Payment, 69),
    IconOption("receipt", Icons.Rounded.Receipt, 70),
    IconOption("attach_money", Icons.Rounded.AttachMoney, 71)
)

// Creative icons (IDs 72-76)
val CREATIVE_ICONS = listOf(
    IconOption("brush", Icons.Rounded.Brush, 72),
    IconOption("music_note", Icons.Rounded.MusicNote, 73),
    IconOption("camera", Icons.Rounded.CameraAlt, 74),
    IconOption("edit", Icons.Rounded.Edit, 75),
    IconOption("design_services", Icons.Rounded.DesignServices, 76)
)

// Transportation icons (IDs 77-79)
val TRANSPORTATION_ICONS = listOf(
    IconOption("directions_car", Icons.Rounded.DirectionsCar, 77),
    IconOption("train", Icons.Rounded.Train, 78),
    IconOption("flight", Icons.Rounded.Flight, 79)
)

// Entertainment icons (IDs 80-82)
val ENTERTAINMENT_ICONS = listOf(
    IconOption("movie", Icons.Rounded.Movie, 80),
    IconOption("tv", Icons.Rounded.Tv, 81),
    IconOption("sports_esports", Icons.Rounded.SportsEsports, 82)
)

// Household icons (IDs 83-85)
val HOUSEHOLD_ICONS = listOf(
    IconOption("kitchen", Icons.Rounded.Kitchen, 83),
    IconOption("build", Icons.Rounded.Build, 84),
    IconOption("iron", Icons.Rounded.Iron, 85)
)

// Outdoor icons (IDs 86-88)
val OUTDOOR_ICONS = listOf(
    IconOption("park", Icons.Rounded.Park, 86),
    IconOption("landscape", Icons.Rounded.Landscape, 87),
    IconOption("terrain", Icons.Rounded.Terrain, 88)
)

// Technology icons (IDs 89-91)
val TECHNOLOGY_ICONS = listOf(
    IconOption("phone", Icons.Rounded.Phone, 89),
    IconOption("computer", Icons.Rounded.Computer, 90),
    IconOption("devices", Icons.Rounded.Devices, 91)
)

val ALL_CATEGORIES = listOf(
    IconCategory.FITNESS,
    IconCategory.HEALTH,
    IconCategory.LEARNING,
    IconCategory.MENTAL,
    IconCategory.LIFESTYLE,
    IconCategory.TODO,
    IconCategory.PERSONAL_HYGIENE,
    IconCategory.SOCIAL,
    IconCategory.FINANCE,
    IconCategory.CREATIVE,
    IconCategory.TRANSPORTATION,
    IconCategory.ENTERTAINMENT,
    IconCategory.HOUSEHOLD,
    IconCategory.OUTDOOR,
    IconCategory.TECHNOLOGY
)

val CATEGORY_ICONS = mapOf(
    IconCategory.FITNESS to FITNESS_ICONS,
    IconCategory.HEALTH to HEALTH_ICONS,
    IconCategory.LEARNING to LEARNING_ICONS,
    IconCategory.MENTAL to MENTAL_ICONS,
    IconCategory.LIFESTYLE to LIFESTYLE_ICONS,
    IconCategory.TODO to TODO_ICONS,
    IconCategory.PERSONAL_HYGIENE to PERSONAL_HYGIENE_ICONS,
    IconCategory.SOCIAL to SOCIAL_ICONS,
    IconCategory.FINANCE to FINANCE_ICONS,
    IconCategory.CREATIVE to CREATIVE_ICONS,
    IconCategory.TRANSPORTATION to TRANSPORTATION_ICONS,
    IconCategory.ENTERTAINMENT to ENTERTAINMENT_ICONS,
    IconCategory.HOUSEHOLD to HOUSEHOLD_ICONS,
    IconCategory.OUTDOOR to OUTDOOR_ICONS,
    IconCategory.TECHNOLOGY to TECHNOLOGY_ICONS
)

@Composable
fun IconPicker(
    selectedIconId: Int,
    onIconSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(IconCategory.FITNESS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_metric_select_icon)) },
        text = {
            Column {
                ScrollableTabRow(
                    selectedTabIndex = ALL_CATEGORIES.indexOf(selectedCategory),
                    edgePadding = 16.dp
                ) {
                    ALL_CATEGORIES.forEach { category ->
                        Tab(
                            selected = category == selectedCategory,
                            onClick = { selectedCategory = category },
                            text = { Text(stringResource(category.stringResId)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(300.dp)
                ) {
                    val icons = CATEGORY_ICONS[selectedCategory] ?: emptyList()
                    items(icons.size) { index ->
                        val icon = icons[index]
                        Icon(
                            imageVector = icon.icon,
                            contentDescription = icon.name,
                            modifier = Modifier
                                .size(48.dp)
                                .clickable {
                                    onIconSelected(icon.id)
                                    onDismiss()
                                },
                            tint = if (icon.id == selectedIconId)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}
