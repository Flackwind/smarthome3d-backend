package it.smarthome3d.auth;

import it.smarthome3d.api.ApiException;
import it.smarthome3d.storage.ProjectValidator;
import java.security.SecureRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The current application has one owner (id 1), shared by their authenticated sessions. */
@Service
public class OwnerProfile {
    private final JdbcTemplate db;
    public OwnerProfile(JdbcTemplate db){this.db=db;}
    public synchronized CredentialKey unlock(String password){ensure();return CredentialKey.derive(password,db.queryForObject("SELECT key_salt FROM owner_profile WHERE owner_id=1",byte[].class));}
    private void ensure(){
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM owner_profile WHERE owner_id=1)",Boolean.class)))return;
        byte[] salt=new byte[32];new SecureRandom().nextBytes(salt);
        try{db.update("INSERT INTO owner_profile(owner_id,key_salt) VALUES (1,?)",salt);}catch(DuplicateKeyException ignored){/* Another successful owner login initialized it. */}
    }
    public String activeProject(){return db.query("SELECT p.id FROM owner_profile o JOIN projects p ON p.id=o.active_project_id WHERE o.owner_id=1",(rs,n)->rs.getString(1)).stream().findFirst().orElse("");}
    public synchronized void selectProject(String id){
        ProjectValidator.key(id);if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE id=?)",Boolean.class,id)))throw ApiException.missing();
        ensure();db.update("UPDATE owner_profile SET active_project_id=? WHERE owner_id=1",id);
    }
}
