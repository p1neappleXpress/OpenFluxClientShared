package io.openflux.app.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme

@Composable
private fun fieldColors() = AppTheme.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, disabledTextColor = c.textMuted,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surface,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.border, errorBorderColor = c.danger,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.textSecondary, errorLabelColor = c.danger,
        cursorColor = c.accent, errorSupportingTextColor = c.danger,
        focusedPlaceholderColor = c.textMuted, unfocusedPlaceholderColor = c.textMuted,
    )
}

/** A text field; [error] goes under it in one line. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) = TextInput(value, onValueChange, label, modifier, placeholder, error, singleLine, mono, keyboardType, trailing, VisualTransformation.None)

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    placeholder: String?,
    error: String?,
    singleLine: Boolean,
    mono: Boolean,
    keyboardType: KeyboardType,
    trailing: (@Composable () -> Unit)?,
    transformation: VisualTransformation,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it, maxLines = 2) } },
        singleLine = singleLine,
        textStyle = if (mono) AppTheme.type.mono else AppTheme.type.body,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        visualTransformation = transformation,
        shape = RoundedCornerShape(AppTheme.shapes.small),
        colors = fieldColors(),
    )
}

/** A password or a key: hidden until the eye is pressed. */
@Composable
fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null) {
    var shown by rememberSaveable { mutableStateOf(false) }
    TextInput(
        value, onValueChange, label, modifier, null, error, singleLine = true, mono = false, KeyboardType.Password,
        trailing = {
            IconAction(
                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (shown) "Скрыть" else "Показать",
                onClick = { shown = !shown },
            )
        },
        transformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
    )
}

/** One of a few: L4 / L3, Пароль / Ключ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    val c = AppTheme.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                enabled = enabled(option),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.accent, activeContentColor = c.onAccent, activeBorderColor = c.accent,
                    inactiveContainerColor = c.surface, inactiveContentColor = c.text, inactiveBorderColor = c.border,
                    disabledInactiveContentColor = c.textMuted, disabledInactiveBorderColor = c.border,
                ),
            ) {
                // A narrow screen shrinks a long label rather than cutting it.
                BasicText(
                    label(option),
                    style = AppTheme.type.body.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = AppTheme.type.body.fontSize, stepSize = 0.5.sp),
                )
            }
        }
    }
}

/** A switch on a row: the whole row toggles it. */
@Composable
fun Toggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    val c = AppTheme.colors
    RowItem(
        title,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                    uncheckedThumbColor = c.textSecondary, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.border,
                ),
            )
        },
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val c = AppTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(text, style = AppTheme.type.bodySmall, maxLines = 1) },
        leadingIcon = leading?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        shape = RoundedCornerShape(AppTheme.shapes.large),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.surface, labelColor = c.text, iconColor = c.textSecondary,
            selectedContainerColor = c.accent, selectedLabelColor = c.onAccent, selectedLeadingIconColor = c.onAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = c.border, selectedBorderColor = c.accent),
    )
}
