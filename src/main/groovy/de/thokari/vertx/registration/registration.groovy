package de.thokari.vertx.registration

import io.vertx.groovy.ext.auth.jwt.JWTAuth
import io.vertx.groovy.ext.jdbc.JDBCClient
import io.vertx.groovy.ext.mail.MailClient
import org.mindrot.jbcrypt.BCrypt

def registrationConfig = vertx.currentContext().config()

def authConfig = [
    keyStore: [
        path: 'keystore.jceks',
        type: 'jceks',
        password: 'password'
    ]
]
def jdbcConfig = [
    url: "jdbc:postgresql:${registrationConfig.dbName}",
    driver_class: 'org.postgresql.Driver'
]

def authProvider = JWTAuth.create(vertx, authConfig)
def dbClient = JDBCClient.createShared(vertx, jdbcConfig)
def mailClient = MailClient.createShared(vertx, registrationConfig.emailServer, 'MailClient')
def eb = vertx.eventBus()

eb.consumer 'registration.register', { msg ->
    def email = msg.body().email
    def password = msg.body().password
    def passwordConfirm = msg.body().passwordConfirm
    if (!email.contains('@')) {
        replyError msg, 'Invalid email address'
    } else if (password.size() < 8) {
        replyError msg, 'Password too short'
    } else if (!(password.equals(passwordConfirm))) {
        replyError msg, 'Passwort not repeated correctly'
    } else {
        def token = generateToken(authProvider, email)
        saveRegistration(dbClient, email, password, { res1 ->
            if (res1.succeeded()) {
                sendEmail(mailClient, email, token, { res2 ->
                    if (res2.succeeded()) {
                        replySuccess msg, email, token
                    } else {
                        replyError msg, res2.cause().message
                    }
                })
            } else {
                replyError msg, res1.cause().message
            }
        })
    }
}

eb.consumer 'registration.confirm', { msg ->
    def token = msg.body().token
    authProvider.authenticate([ jwt: token ], { res ->
        if (res.succeeded()) {
            println res.result()
        } else {

        }

    })


}

def sendEmail (mailClient, email, token, cb) {
    def mail = [ from: 'registration@sendandstore.de', to: email ]
    println "Sending email to $email"
    mailClient.sendMail(mail, cb)
}

def generateToken (authProvider, email) {
    def duration = 1000 * 60 * 60 * 24 // 1 day
    def payload = [ email: email, exp: new Date().time + duration ]
    authProvider.generateToken payload, [:]
}

def replyError (msg, errorMsg) {
    msg.reply([
        status: 'error',
        message: errorMsg
    ])
}

def replySuccess (msg, email, token) {
    msg.reply([
        status: 'ok',
        email: email,
        token: token
    ])
}

def saveRegistration (dbClient, email, password, cb) {
    dbClient.getConnection({ res ->
        if (res.succeeded()) {
            def connection = res.result()
            def hash = BCrypt.hashpw(password, BCrypt.gensalt())
            java.sql.Date sqlDate = new java.sql.Date(new java.util.Date().time)
            def params = [ email, hash, sqlDate ]
            connection.updateWithParams('INSERT INTO registration (email, password, created) VALUES (?, ?, ?)', params, cb)
        } else {
            throw res.cause()
        }
    })
}

/*
// def dnsClient = vertx.createDnsClient(53, registrationConfig.dnsGateway)
// def tcpClient = vertx.createNetClient()

def lookupEmailAddress (dnsClient, tcpClient, address, callback) {
    def (name, domain) = address.split('@')
    dnsClient.resolveMX(domain, { res1 ->
        if (res1.succeeded()) {
            def records = res1.result()
            def defaultServer = records[0].name()
            tcpClient.connect(25, defaultServer, { res2 ->
                if (res2.succeeded()) {
                    def socket = res2.result()
                    socket.write("HELO\nmail from:<check@abc.de>\nrcpt to:<$name@$domain>\n")
                    def okCount = 0
                    def called = true
                    socket.handler({ buf ->
                        if (buf.toString().startsWith('5') && !called) {
                            called = true
                            callback(false)
                        } else {
                            okCount++
                        }
                        if (okCount == 3 && !called) {
                            called = true
                            callback(false)
                        }
                    })
                } else {
                    println "Failed to connect to $defaultServer"
                }
            })
        } else {
            println "Failed to resolve entry $domain"
        }
    })
}
*/
