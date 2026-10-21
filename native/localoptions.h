/* fSSH build options */
#define DROPBEAR_REEXEC 0
#define DROPBEAR_SVR_AGENTFWD 0
#define DROPBEAR_CLI_AGENTFWD 0
#define DROPBEAR_SVR_REMOTETCPFWD 0
#define DROPBEAR_SVR_REMOTESTREAMFWD 0
#define DROPBEAR_SK_KEYS 0
#define DROPBEAR_X11FWD 0
#define DO_MOTD 0
#define DROPBEAR_SVR_PASSWORD_AUTH 1
#define DROPBEAR_SVR_PUBKEY_AUTH 1
#define DROPBEAR_DELAY_HOSTKEY 1
#define DROPBEAR_SFTPSERVER 1
#define SFTPSERVER_PATH fssh_sftp_path()
#define DEFAULT_PATH fssh_default_path()
#define DEFAULT_ROOT_PATH fssh_default_path()
/* compatibility with old clients */
#define DROPBEAR_RSA_SHA1 1
#define DROPBEAR_DH_GROUP14_SHA1 1
#define DROPBEAR_SHA1_HMAC 1
#define DROPBEAR_ENABLE_CBC_MODE 1
#define DROPBEAR_AES128 1
#define DROPBEAR_AES256 1
#define DROPBEAR_3DES 1
#define RSA_PRIV_FILENAME fssh_hostkey(0)
#define DSS_PRIV_FILENAME fssh_hostkey(1)
#define ECDSA_PRIV_FILENAME fssh_hostkey(2)
#define ED25519_PRIV_FILENAME fssh_hostkey(3)
