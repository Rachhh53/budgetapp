; Uninstall keeps the user's data unless they tick "Delete the application data".
;
; Tauri's uninstaller already shows that checkbox, but it only knows the folders named
; after the app identifier. The budget database, receipts, backups and logs live in
; %APPDATA%\Budget (written by the Java backend), so that folder is removed here — only
; when the box is ticked, and never during an upgrade, which runs the uninstaller silently.

!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    RMDir /r "$APPDATA\Budget"
  ${EndIf}
!macroend
