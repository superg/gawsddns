# gawsddns - AWS Dynamic DNS Service

A serverless Dynamic DNS (DDNS) service built with AWS CDK, featuring a Java Lambda function behind an API Gateway that automatically updates DNS records in Route53. This service allows you to keep a domain name pointing to your changing IP address, perfect for home servers, IoT devices, or any service with a dynamic IP.

## 🏗️ Architecture

- **AWS Lambda** (Java 17): Handles DNS update requests and Route53 operations (will be rewritten in Rust)
- **API Gateway**: RESTful endpoint with custom domain and SSL certificate
- **Route53**: DNS record management for both IPv4 (A) and IPv6 (AAAA) records
- **SSM Parameter Store**: Secure storage for authentication credentials (used instead of Secrets Manager for cost optimization)
- **AWS CDK**: Infrastructure as Code deployment

## � DynDNS Implementation Details

This service implements the DynDNS Remote Access API based on the [official specification](https://help.dyn.com/remote-access-api.html) with several enhancements and limitations:

### ✅ Supported Features

- **Standard DynDNS v2/v3 Endpoints**: Both `/nic/update` and `/v3/update` are fully supported
- **Multi-hostname Support**: Update multiple hostnames in a single request
- **Multi-IP Support**: Set multiple IP addresses for a single hostname in one request
- **IPv6 Support**: Complete IPv6 support for both endpoint versions (enhancement over standard)
- **Mixed Protocol Updates**: Single requests can contain both IPv4 and IPv6 addresses
- **Intelligent Record Management**: Service automatically updates appropriate A and AAAA DNS records
- **Independent Updates**: IPv4 and IPv6 updates are independent - updating one type won't affect the other
- **Auto-Creation**: Hostnames don't need to exist in DNS beforehand - they're created on first request
- **Automatic IP Detection**: Infers client IP if not explicitly provided
- **Smart Updates**: Records are only modified when IP addresses actually change
- **Basic Authentication**: Secure username/password authentication
- **SSL/TLS Enabled**: Custom domain with SSL certificate
- **Serverless**: Pay-per-use Lambda function with automatic scaling

### ⚠️ Limitations & Differences

- **HTTPS Only**: HTTP is not supported due to API Gateway limitations (and shouldn't be used for security reasons)
- **Limited Parameters**: Only `hostname` and `myip` parameters are processed; all others are ignored
- **User Agent Ignored**: The `User-Agent` header is not processed or validated
- **Simplified Response Codes**: Some less relevant return codes from the original specification are not implemented

### 🔧 Protocol Behavior

- **Record Preservation**: IPv4-only updates preserve existing IPv6 records and vice versa
- **Validation**: All hostnames and IP addresses are validated before processing

## �🚀 Quick Start

### Prerequisites

- AWS CLI configured with appropriate permissions
- Node.js 18+ and npm
- Java 17+ and Maven
- A domain name hosted in Route53
- An SSL certificate in AWS Certificate Manager

### Installation

1. **Clone the repository**

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Configure the service**
   ```bash
   cp config.example.json config.json
   ```
   
   Edit `config.json` with your AWS account details, domain configuration, and authentication credentials.

4. **Deploy to AWS**
   ```bash
   npm run deploy
   ```

## Client Configuration Examples

For automated updates, you can configure various DDNS clients:

#### ddclient (`/etc/ddclient.conf`)

**IPv4 Configuration:**
```ini
protocol=dyndns2
ssl=yes
use=if, if=eth0
server=members.yourdomain.com
login=yourusername
password='yourpassword'
host.yourdomain.com
```

**IPv6 Configuration:**
```ini
protocol=dyndns2
ssl=yes
usev6=ifv6, if=eth0
server=members.yourdomain.com
login=yourusername
password='yourpassword'
host.yourdomain.com
```

#### inadyn (`/etc/inadyn.conf`)

**IPv4 Configuration:**
```ini
custom yourdomain {
    username    = yourusername
    password    = yourpassword
    ddns-server = members.yourdomain.com
    ddns-path   = "/nic/update?hostname=%h.yourdomain.com&myip=%i"
    hostname    = host
}
```

**IPv6 Configuration:**
```ini
allow-ipv6 = true
custom yourdomain {
    username    = yourusername
    password    = yourpassword
    ddns-server = members.yourdomain.com
    ddns-path   = "/nic/update?hostname=%h.yourdomain.com&myip=%i"
    hostname    = host
    checkip-command = "/sbin/ip -6 addr | grep inet6 | awk -F '[ \t]+|/' '{print $3}' | grep -v ^::1 | grep -v ^fe80"
}
```

#### dnsupdate (`/etc/dnsupdate.conf`)

**IPv4/IPv6 Configuration:**
```yaml
dns_services:
    - type: StandardService
      args:
          service_ipv4: members.yourdomain.com
          service_ipv6: members.yourdomain.com
          username: yourusername
          password: yourpassword
          hostname: host.yourdomain.com
```

#### Ubiquiti UniFi Network Controller

Configure Dynamic DNS through the UniFi Controller web interface (inadyn-based):

| Field | Value |
|-------|-------|
| **Service** | Custom |
| **Hostname** | host |
| **Username** | yourusername |
| **Password** | yourpassword |
| **Server** | `members.yourdomain.com/nic/update?hostname=%h.yourdomain.com&myip=%i` |

*Note: UniFi merges inadyn's `ddns-server` and `ddns-path` settings into a single "Server" field.*

## Manual Updates

You can manually update DNS records using HTTP requests:

**Basic Update (auto-detect IP):**
```bash
curl -u yourusername:yourpassword "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com"
```

**Update with specific IP:**
```bash
curl -u yourusername:yourpassword "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com&myip=192.168.1.1"
```

**Update multiple hostnames:**
```bash
curl -u yourusername:yourpassword "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com,server.yourdomain.com&myip=192.168.1.1"
```

**IPv6 Support:**
```bash
curl -u yourusername:yourpassword "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com&myip=2001:db8::1"
```

**Multiple IPs for single hostname:**
```bash
curl -u yourusername:yourpassword "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com&myip=192.168.1.1,192.168.1.2,2001:db8::1"
```

**Windows PowerShell:**
```powershell
Invoke-WebRequest "https://members.yourdomain.com/nic/update?hostname=host.yourdomain.com&myip=192.168.1.1" -Headers @{Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("yourusername:yourpassword"))}
```

## 🔒 Security

- Credentials stored securely in AWS SSM Parameter Store
- SSL/TLS encryption for all API communication
- IAM roles with minimal required permissions
- Basic authentication for API access

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## 📄 License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE.txt](LICENSE.txt) file for details.

## 👨‍💻 Author

**Hennadiy Brych** - [gennadiy.brych@gmail.com](mailto:gennadiy.brych@gmail.com)

---

**Need help?** Open an issue or check the AWS CloudWatch logs for your Lambda function.
