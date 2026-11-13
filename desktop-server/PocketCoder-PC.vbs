Set shell = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
folder = fs.GetParentFolderName(WScript.ScriptFullName)
exe = fs.BuildPath(folder, "PocketCoder-PC.exe")
If fs.FileExists(exe) Then
  shell.Run """" & exe & """", 1, False
Else
  script = fs.BuildPath(folder, "pocketcoder-pc.ps1")
  shell.Run "powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & script & """", 1, False
End If
