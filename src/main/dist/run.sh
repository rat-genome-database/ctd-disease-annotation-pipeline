#!/usr/bin/env bash
. /etc/profile

APPNAME=CTDDisease
APPDIR=/home/rgddata/pipelines/$APPNAME
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

EMAIL_LIST=mtutaj@mcw.edu
if [ "$SERVER" == "REED" ]; then
  EMAIL_LIST=mtutaj@mcw.edu,jrsmith@mcw.edu,slaulederkind@mcw.edu
fi

cd $APPDIR
pwd
DB_OPTS="-Dspring.config=$APPDIR/../properties/default_db.xml"
LOG4J_OPTS="-Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml"
export CTD_DISEASE_OPTS="$DB_OPTS $LOG4J_OPTS"
bin/$APPNAME "$@" 2>&1

mailx -s "[$SERVER] CTDDisease ok" $EMAIL_LIST < $APPDIR/logs/status.log
