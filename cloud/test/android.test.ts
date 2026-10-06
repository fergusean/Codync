import { applyD1Migrations } from "cloudflare:test";
import { env as rawEnv } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { call, clerkToken, env } from "./helpers";
import * as ref from "./ref";

describe("Android devices", () => {
  it("registers a signed Android client and preserves revocation", async () => {
    const userId = "user_android_" + ref.b64url(ref.random(8));
    const token = await clerkToken(userId);
    const key = ref.signKey();
    const registered = await call("POST", "/v1/devices", { token, key, body: { name: "Samsung", platform: "android" } });
    expect(registered.status).toBe(200);
    expect(registered.body.device.platform).toBe("android");
    const listed = await call("GET", "/v1/devices", { token });
    expect(listed.body.devices[0].platform).toBe("android");
    const revoked = await call("DELETE", "/v1/devices/" + registered.body.device.deviceId, { token });
    expect(revoked.status).toBe(200);
    const refused = await call("POST", "/v1/devices", { token, key, body: { name: "Samsung", platform: "android" } });
    expect(refused.status).toBe(403);
  });

  it("migrates populated iOS, Mac and Windows authorizations without losing constraints", async () => {
    const db = (rawEnv as unknown as { ANDROID_MIGRATION_DB: D1Database }).ANDROID_MIGRATION_DB;
    await applyD1Migrations(db, env.TEST_MIGRATIONS.slice(0, 2));
    await db.batch([
      db.prepare("INSERT INTO accounts(user_id,created_at) VALUES ('android-owner',1)"),
      db.prepare("INSERT INTO computers(id,sign_pub,box_pub,owner_user_id,name,platform,version,created_at,updated_at) VALUES ('host','sign','box','android-owner','Host','linux','2.4.0',1,1)"),
      ...["ios", "macos", "windows"].map(platform => db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at,last_used_at,revoked_at) VALUES (?, 'android-owner', ?, ?, ?, 1, 2, 3)").bind(platform, platform + "-key", platform, platform)),
      db.prepare("INSERT INTO grants(id,computer_id,device_id,scopes,status,created_at) VALUES ('grant','host','ios','[\"control\"]','revoked',1)"),
      db.prepare("INSERT INTO access_requests(id,computer_id,device_id,user_id,status,commit_hash,created_at,expires_at) VALUES ('request','host','windows','android-owner','denied','hash',1,2)"),
    ]);
    await applyD1Migrations(db, env.TEST_MIGRATIONS.slice(2));
    const old = await db.prepare("SELECT platform,created_at,last_used_at,revoked_at FROM devices ORDER BY platform").all();
    expect(old.results).toEqual(["ios", "macos", "windows"].map(platform => ({ platform, created_at: 1, last_used_at: 2, revoked_at: 3 })));
    const grant = await db.prepare("SELECT device_id FROM grants").first();
    const request = await db.prepare("SELECT device_id FROM access_requests").first();
    expect(grant).toEqual({ device_id: "ios" });
    expect(request).toEqual({ device_id: "windows" });
    const foreignKeys = await db.prepare("PRAGMA foreign_key_check").all();
    expect(foreignKeys.results).toEqual([]);
    await db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at) VALUES ('android','android-owner','android-key','Samsung','android',1)").run();
    await expect(db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at) VALUES ('duplicate','android-owner','android-key','Duplicate','android',1)").run()).rejects.toThrow();
    await expect(db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at) VALUES ('other','missing','other-key','Other','android',1)").run()).rejects.toThrow();
  });
});
