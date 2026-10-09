import { applyD1Migrations } from "cloudflare:test";
import { env as rawEnv } from "cloudflare:workers";
import { expect, it } from "vitest";
import { env } from "./helpers";

it("preserves existing Android devices and authorizations when adding desktop platforms", async () => {
  const db = (rawEnv as unknown as { ANDROID_MIGRATION_DB: D1Database }).ANDROID_MIGRATION_DB;
  const desktop = env.TEST_MIGRATIONS.findIndex(migration => migration.name === "0003_desktop_devices.sql");
  expect(desktop).toBeGreaterThan(0);
  await applyD1Migrations(db, env.TEST_MIGRATIONS.slice(0, desktop));
  await db.batch([
    db.prepare("INSERT INTO accounts(user_id,created_at) VALUES ('owner',1)"),
    db.prepare("INSERT INTO computers(id,sign_pub,box_pub,owner_user_id,name,platform,version,created_at,updated_at) VALUES ('host','sign','box','owner','Host','linux','2.9.0',1,1)"),
    db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at,last_used_at,revoked_at) VALUES ('android','owner','android-key','Phone','android',1,2,3)"),
    db.prepare("INSERT INTO grants(id,computer_id,device_id,scopes,status,created_at) VALUES ('grant','host','android','[\"control\"]','revoked',1)"),
    db.prepare("INSERT INTO access_requests(id,computer_id,device_id,user_id,status,commit_hash,created_at,expires_at) VALUES ('request','host','android','owner','denied','hash',1,2)"),
  ]);
  const tables = ["devices", "grants", "access_requests"];
  const before = await Promise.all(tables.map(table => db.prepare(`SELECT * FROM ${table} ORDER BY id`).all()));
  await applyD1Migrations(db, env.TEST_MIGRATIONS.slice(desktop));
  const after = await Promise.all(tables.map(table => db.prepare(`SELECT * FROM ${table} ORDER BY id`).all()));
  expect(after.map(result => result.results)).toEqual(before.map(result => result.results));
  const foreignKeys = await db.prepare("PRAGMA foreign_key_check").all();
  expect(foreignKeys.results).toEqual([]);
  for (const platform of ["linux", "windows"]) {
    await db.prepare("INSERT INTO devices(id,owner_user_id,sign_pub,name,platform,created_at) VALUES (?, 'owner', ?, ?, ?, 1)")
      .bind(platform, platform + "-key", platform, platform).run();
  }
});
