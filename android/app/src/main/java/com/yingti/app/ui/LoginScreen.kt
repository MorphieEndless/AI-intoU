package com.yingti.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    initialUsername: String,
    initialServer: String,
    loading: Boolean,
    error: String?,
    onLogin: (String, String, String) -> Unit,
) {
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var server by remember(initialServer) { mutableStateOf(initialServer) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("樱媞 Bridge", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("一条链路，直达 SX589B", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") }, leadingIcon = { Icon(Icons.Outlined.Person, null) }, singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = server, onValueChange = { server = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") }, singleLine = true,
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(username.trim(), password, server.trim()) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !loading && username.isNotBlank() && password.isNotBlank() && server.isNotBlank(),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("登录并启动")
            }
        }
    }
}
