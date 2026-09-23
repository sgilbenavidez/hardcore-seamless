<#
.SYNOPSIS
    Minimal Source RCON protocol client (auth + exec, one command per connection).
    Used for diagnostics and for a clean, verified Server A shutdown (see stop-server-a.ps1).

.USAGE
    . "$PSScriptRoot\rcon-client.ps1"
    Invoke-Rcon -RconHost 127.0.0.1 -Port 25576 -Password $pw -Command "list"
#>

function Invoke-Rcon {
    param(
        [string]$RconHost = "127.0.0.1",
        [Parameter(Mandatory=$true)][int]$Port,
        [Parameter(Mandatory=$true)][string]$Password,
        [Parameter(Mandatory=$true)][string]$Command,
        [int]$TimeoutMs = 5000
    )

    function Send-RconPacket {
        param([System.Net.Sockets.NetworkStream]$Stream, [int]$Id, [int]$Type, [string]$Body)
        $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($Body)
        $payloadLen = 4 + 4 + $bodyBytes.Length + 2
        $ms = New-Object System.IO.MemoryStream
        $bw = New-Object System.IO.BinaryWriter($ms)
        $bw.Write([int32]$payloadLen)
        $bw.Write([int32]$Id)
        $bw.Write([int32]$Type)
        $bw.Write($bodyBytes)
        $bw.Write([byte]0)
        $bw.Write([byte]0)
        $bw.Flush()
        $bytes = $ms.ToArray()
        $Stream.Write($bytes, 0, $bytes.Length)
    }

    function Read-RconPacket {
        param([System.Net.Sockets.NetworkStream]$Stream)
        $lenBytes = New-Object byte[] 4
        $read = $Stream.Read($lenBytes, 0, 4)
        if ($read -lt 4) { return $null }
        $len = [BitConverter]::ToInt32($lenBytes, 0)
        $rest = New-Object byte[] $len
        $offset = 0
        while ($offset -lt $len) {
            $n = $Stream.Read($rest, $offset, $len - $offset)
            if ($n -le 0) { break }
            $offset += $n
        }
        $id = [BitConverter]::ToInt32($rest, 0)
        $type = [BitConverter]::ToInt32($rest, 4)
        $bodyLen = $len - 4 - 4 - 2
        $body = ""
        if ($bodyLen -gt 0) {
            $body = [System.Text.Encoding]::ASCII.GetString($rest, 8, $bodyLen)
        }
        return @{ Id = $id; Type = $type; Body = $body }
    }

    $client = New-Object System.Net.Sockets.TcpClient
    $connectTask = $client.ConnectAsync($RconHost, $Port)
    if (-not $connectTask.Wait($TimeoutMs)) {
        throw "RCON connect timed out to ${RconHost}:${Port}"
    }
    $client.ReceiveTimeout = $TimeoutMs
    $client.SendTimeout = $TimeoutMs
    $stream = $client.GetStream()

    try {
        # SERVERDATA_AUTH = 3
        Send-RconPacket -Stream $stream -Id 1 -Type 3 -Body $Password

        # Server may send an empty SERVERDATA_RESPONSE_VALUE (type 0) before AUTH_RESPONSE (type 2).
        $authPacket = Read-RconPacket -Stream $stream
        if ($authPacket.Type -eq 0) {
            $authPacket = Read-RconPacket -Stream $stream
        }

        if ($null -eq $authPacket -or $authPacket.Id -eq -1) {
            throw "RCON authentication failed (bad password or server rejected connection)."
        }

        # SERVERDATA_EXECCOMMAND = 2
        Send-RconPacket -Stream $stream -Id 2 -Type 2 -Body $Command
        $respPacket = Read-RconPacket -Stream $stream

        return $respPacket.Body
    } finally {
        $stream.Close()
        $client.Close()
    }
}
