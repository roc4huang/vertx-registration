package de.thokari.vertx.registration

import io.vertx.groovy.ext.auth.jwt.JWTAuth
import io.vertx.groovy.ext.jdbc.JDBCClient
import io.vertx.groovy.ext.mail.MailClient
import io.vertx.groovy.core.Future
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
    def permissions = msg.body().permissions // TODO save this
    if (!email.contains('@')) {
        replyError msg, 'Invalid email address'
    } else if (password.size() < 8) {
        replyError msg, 'Password too short'
    } else if (!(password.equals(passwordConfirm))) {
        replyError msg, 'Passwort not repeated correctly'
    } else {
        def token = generateRegistrationToken(authProvider, email)
        saveRegistration(dbClient, email, password, permissions, { res1 ->
            if (res1.succeeded()) {
                sendEmail(mailClient, email, token, { res2 ->
                    if (res2.succeeded()) {
                        replySuccess msg, [ email: email, token: token ]
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
    authProvider.authenticate([ jwt: token ], { authRes ->
        if (authRes.succeeded()) {
            def user = authRes.result()
            def payload = user.principal()
            def email = payload.email
            confirmEmail(dbClient, email, { confRes ->
                if (confRes.succeeded()) {
                    replySuccess msg, null
                } else {
                    replyError msg, confRes.cause().message
                }
            })
        } else {
            def errorMsg = authRes.cause().message ?: "Could not validate token '$token'"
            replyError msg, errorMsg
        }
    })
}

eb.consumer 'registration.login', { msg ->
    def email = msg.body().email
    def candidate = msg.body().password
    def permissions = msg.body().permissions
    comparePassword (dbClient, email, candidate, { res1 ->
        if (res1.succeeded()) {
            def token = generateLoginToken(authProvider, email, permissions)
            replySuccess msg, [ token: token ]
        } else {
            replyError msg, res1.cause().message
        }
    })
}

def sendEmail (mailClient, email, token, cb) {
    def mail = [ from: 'registration@sendandstore.de', to: email ]
    println "Sending email to $email"
    mailClient.sendMail(mail, cb)
}

def generateRegistrationToken (authProvider, email) {
    def payload = [ email: email ]
    // TODO not fake permissions
    def options = [ permissions: ['admin'], expiresInMinutes: 60 * 24 ]
    authProvider.generateToken payload, options
}

def generateLoginToken (authProvider, email, permissions) {
    def payload = [ email: email ]
    // TODO define at registration...
    def options = [ permissions: permissions, expiresInMinutes: 1 ]
    authProvider.generateToken payload, options
}

def saveRegistration (dbClient, email, password, permissions, cb) {
    withConnection(dbClient, { conn ->
        // this might actually take some time, better not block
        vertx.executeBlocking({ future ->
            future.complete(BCrypt.hashpw(password, BCrypt.gensalt()))
        }, { res ->
            if (res.succeeded()) {
                def hash = res.result()
                java.sql.Date sqlDate = new java.sql.Date(new java.util.Date().time)
                def params = [ email, false, hash, sqlDate ]
                // TODO enter permissions
                conn.updateWithParams('INSERT INTO registration (email, email_confirmed, password, created) VALUES (?, ?, ?, ?)', params, cb)
            } else {
                cb(res)
            }
        })
    })
}

def confirmEmail (dbClient, email, cb) {
    withConnection(dbClient, { conn ->
        conn.queryWithParams('SELECT email_confirmed FROM registration WHERE email = ?', [ email ], { res1 ->
            if (res1.succeeded()) {
                def emailConfirmed = res1.result().results[0][0]
                if (emailConfirmed == true) {
                    cb(Future.fail('Email address already confirmed'))
                } else {
                    conn.updateWithParams('UPDATE registration SET email_confirmed = ? WHERE email = ?', [ true, email ], cb)
                }
            } else {
                cb(res1)
            }
        })
    })
}

def comparePassword (dbClient, email, candidate, cb) {
    withConnection(dbClient, { conn ->
        println 'EMAIL' + email
        conn.queryWithParams('SELECT password FROM registration WHERE email = ?', [ email ], { res1 ->
            if (res1.succeeded()) {
                println res1.result()
                def hashed = res1.result().results[0][0]
                vertx.executeBlocking({ future ->
                    if (BCrypt.checkpw(candidate, hashed)) {
                        future.complete(true)
                    } else {
                        future.fail('Passwords do not match')
                    }
                }, { res2 ->
                    cb(res2)
                })
            } else {
                cb(res1)
            }
        })
    })
}

def replyError (msg, errorMsg) {
    msg.reply([
        status: 'error',
        message: errorMsg
    ])
}

def replySuccess (msg, content) {
    def body = [ status: 'ok' ]
    if (content) {
        body += content
    }
    msg.reply(body)
}

def withConnection (dbClient, closure) {
    dbClient.getConnection({ res ->
        if (res.succeeded()) {
            closure.call(res.result())
        } else {
            throw res.cause()
        }
    })
}
