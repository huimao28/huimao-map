package com.huimao.map.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.huimao.map.data.ChinaCities

/**
 * 白马地图“选择城市”页同款：顶部搜索框 + “重置为跟随定位城市” +
 * 按省分组的二级菜单（点省份展开城市）。
 *
 * 列表按 performance skill（lazy-layouts §1）给了 key 与 contentType；
 * 展开状态用 Set<String> 存（§2，不用 mutableStateListOf 做 O(N) 查找）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerScreen(
    locatedCity: String,
    onSelect: (String) -> Unit,
    onResetToLocated: () -> Unit,
    onBack: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择城市") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    if (locatedCity.isNotBlank()) {
                        TextButton(onClick = onResetToLocated) { Text("重置为跟随定位城市") }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("搜索城市") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Clear, null) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            if (searchText.isBlank()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "all", contentType = "all") {
                        ListItem(
                            headlineContent = { Text("全国") },
                            modifier = Modifier.clickable { onSelect("全国") }
                        )
                    }
                    ChinaCities.provinces.forEach { prov ->
                        item(key = "prov-${prov.name}", contentType = "province") {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    expanded = if (prov.name in expanded) expanded - prov.name else expanded + prov.name
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (prov.name in expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(prov.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (prov.name in expanded) {
                            items(
                                prov.cities,
                                key = { city -> "city-${prov.name}-$city" },
                                contentType = { "city" }
                            ) { city ->
                                ListItem(
                                    headlineContent = { Text(city) },
                                    modifier = Modifier.clickable { onSelect(city) }.padding(start = 32.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                val filtered = ChinaCities.provinces.flatMap { prov ->
                    prov.cities.map { city -> city to prov.name }
                }.filter { (city, _) -> city.contains(searchText) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        filtered,
                        key = { (city, prov) -> "search-$prov-$city" },
                        contentType = { "city" }
                    ) { (city, prov) ->
                        ListItem(
                            headlineContent = { Text(city) },
                            supportingContent = { Text(prov) },
                            modifier = Modifier.clickable { onSelect(city) }
                        )
                    }
                }
            }
        }
    }
}
